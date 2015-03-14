package com.boldradius.cqrs

import akka.actor._
import akka.contrib.pattern.ShardRegion

import akka.persistence.{RecoveryCompleted, PersistentActor, SnapshotOffer, Update}
import AuctionCommandQueryProtocol._
import com.boldradius.util.Logging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 *
 * This is the Command side of CQRS. This actor receives commands only: AuctionStart and BidPlaced Cmds.
 *
 * These commands are transformed into events and persisted to a cassandra journal.
 * Once the events are persisted, the corresponding view is prompted to update itself from this journal
 * with Update()
 *
 * The state of the auction is encoded in the var auctionStateMaybe: Option[AuctionBidState]
 *
 * A tick message is scheduled to signal the end of the auction
 *
 * This actor will passivate after 1 minute if no messages are received
 *
 */
object BidProcessor {

  case object Tick

  def props(readRegion: ActorRef): Props = Props(new BidProcessor(readRegion))

  sealed trait AuctionEvt {
    val auctionId: String
  }

  case class AuctionStartedEvt(auctionId: String, started: Long, end: Long, initialPrice: Double, prodId: String) extends AuctionEvt

  case class AuctionEndedEvt(auctionId: String, timeStamp: Long) extends AuctionEvt

  case class BidPlacedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) extends AuctionEvt

  case class BidRefusedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) extends AuctionEvt

  case class BidFailedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long, error: String) extends AuctionEvt

  val idExtractor: ShardRegion.IdExtractor = {
    case m: AuctionCmd => (m.auctionId, m)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case m: AuctionCmd => (math.abs(m.auctionId.hashCode) % 100).toString
  }

  val shardName: String = "BidProcessor"
}

class BidProcessor(readRegion: ActorRef) extends PersistentActor with Passivation with Logging {

  import BidProcessor._

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  /** passivate the entity when no activity for 1 minute*/
  context.setReceiveTimeout(1 minute)

  private var auctionStateMaybe: Option[Auction] = None

  /**
   * This formalizes the effects of this processor
   * Each command results in:
   * maybe AuctionEvt,
   * an AuctionAck,
   * maybe newReceive
   */
  private final case class ProcessedCommand(event: Option[AuctionEvt], ack: AuctionAck, newReceive:Option[Receive])


  /**
   * Updates Auction state
   */
  private def updateState(evt: AuctionEvt): Unit = {

    def updateMaybeState(auctionId: String, f: Auction => Auction): Option[Auction] =
      auctionStateMaybe.flatMap(state => Some(f(state)))

    auctionStateMaybe = evt match {
      case AuctionStartedEvt(auctionId, timeStamp, end, initialPrice, prodId) =>
        Some(Auction(auctionId, timeStamp, end, initialPrice, Nil, Nil, false))

      case AuctionEndedEvt(auctionId: String, timeStamp) =>
        updateMaybeState(auctionId, a => a.copy(ended = true))

      case BidPlacedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) =>
        updateMaybeState(auctionId, a => a.copy(acceptedBids = Bid(bidPrice, buyer, timeStamp) :: a.acceptedBids))

      case BidRefusedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) =>
        updateMaybeState(auctionId, a => a.copy(refusedBids = Bid(bidPrice, buyer, timeStamp) :: a.refusedBids))

      case BidFailedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long, error: String) =>
        updateMaybeState(auctionId, a => a.copy(refusedBids = Bid(bidPrice, buyer, timeStamp) :: a.refusedBids))
    }
  }

  private def getCurrentBid(state: Auction): Double =
    state.acceptedBids match {
      case Bid(p, _, _) :: tail => p
      case _ => state.initialPrice
    }


  /**
   *  In an attempt to isolate the effects (write to journal, update state, change receive behaviour),
   *  each case of the PartialFunction[Any,Unit]  Receive functions: initial, takingBids call
   *  handleProcessedCommand ( sender, processedCommand) by convention
   *
   */
  def handleProcessedCommand(sendr: ActorRef, processedCommand: ProcessedCommand): Unit ={

    processedCommand.newReceive.fold({})(context.become)

    processedCommand.event.fold(sender() ! processedCommand.ack) { evt =>
      persist(evt) { persistedEvt =>
        updateState(persistedEvt)
        readRegion ! Update(await = true)
        sendr ! processedCommand.ack
      }
    }
  }

  override def receiveCommand: Receive = passivate(initial).orElse(unknownCommand)

  def initial: Receive = {

    case a@StartAuctionCmd(id, start, end, initialPrice, prodId) =>
      val currentTime = System.currentTimeMillis()

      if (currentTime >= end) {
        handleProcessedCommand(sender(),
          ProcessedCommand(None, InvalidAuctionAck(id, "This auction is already over"), None)
        )
      } else {
        // Starting the auction, schedule a message to signal auction end
        launchLifetime(end)

        handleProcessedCommand(sender(),
          ProcessedCommand(
            Some(AuctionStartedEvt(id, start, end, initialPrice, prodId)),
            StartedAuctionAck(id), Some(passivate(takingBids(id, start, end)).orElse(unknownCommand)))
        )
      }
  }

  def takingBids(auctionId: String, startTime: Long, closeTime: Long): Receive = {

    case Tick => // end of auction
      val currentTime = System.currentTimeMillis()
      persistAsync(AuctionEndedEvt(auctionId, currentTime)) { evt =>
        readRegion ! Update(await = true)
        updateState(evt)
      }
      context.become(passivate(auctionClosed(auctionId, currentTime)).orElse(unknownCommand))

    case a@PlaceBidCmd(id, buyer, bidPrice) => {
      val timestamp = System.currentTimeMillis()

      handleProcessedCommand(sender(),
        auctionStateMaybe.fold(ProcessedCommand(None, AuctionNotYetStartedAck(id),None))(state =>
          if (timestamp < closeTime && timestamp >= startTime) {
            val currentPrice = getCurrentBid(state)
            if (bidPrice > currentPrice) {
              // Successful bid
              ProcessedCommand(
                Some(BidPlacedEvt(id, buyer, bidPrice, timestamp)),
                PlacedBidAck(id, buyer, bidPrice, timestamp),
                None
              )
            } else {
              //Unsuccessful bid
              ProcessedCommand(
                Some(BidRefusedEvt(id, buyer, bidPrice, timestamp)),
                RefusedBidAck(id, buyer, bidPrice, currentPrice),
                None
              )
            }
          } else {
            // auction expired
            if (timestamp < closeTime)
              ProcessedCommand(None, AuctionEndedAck(id),None)
            else
              ProcessedCommand(None, AuctionNotYetStartedAck(id),None)
          }
        )
      )
    }
  }

  def auctionClosed(auctionId: String, closeTime: Long): Receive = {
    case a: PlaceBidCmd => sender() ! AuctionEndedAck(auctionId)
    case a: StartAuctionCmd => sender() ! AuctionEndedAck(auctionId)
  }



  def receiveRecover: Receive = {
    case evt: AuctionEvt => {
      updateState(evt.logDebug("receiveRecover" + _.toString))
    }

    case RecoveryCompleted => {
      auctionStateMaybe.fold[Unit]({}) { auctionState =>
        if (auctionState.logDebug("receiveRecover RecoveryCompleted auctionStateMaybe: " + _.toString).ended)
          context.become(passivate(auctionClosed(auctionState.auctionId, auctionState.endTime)).orElse(unknownCommand))
        else {
          launchLifetime(auctionState.endTime)
          context.become(passivate(takingBids(auctionState.auctionId, auctionState.startTime, auctionState.endTime)).orElse(unknownCommand))
        }
      }
    }

    case SnapshotOffer(_, snapshot) =>
      auctionStateMaybe = snapshot.asInstanceOf[Option[Auction]].logDebug("recovery from snapshot auctionStateMaybe:" + _.toString)
  }


  def unknownCommand: Receive = {
    case other => {
      other.logDebug("unknownCommand: " + _.toString)
      sender() ! InvalidAuctionAck("", "InvalidAuctionAck")
    }
  }

  /** auction lifetime tick will send message when auction is over */
  def launchLifetime(time: Long) = {
    val auctionEnd = (time - System.currentTimeMillis()).logDebug("launchLifetime over in:" + _.toString + "ms")
    if (auctionEnd > 0) {
      context.system.scheduler.scheduleOnce(auctionEnd.milliseconds, self, Tick)
    }
  }
}
