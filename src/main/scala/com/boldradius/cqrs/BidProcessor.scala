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

  /** passivate the entity when no activity for 1 minute */
  context.setReceiveTimeout(1 minute)


  /**
   * This formalizes the effects of this processor
   * Each command results in:
   * maybe AuctionEvt,
   * an AuctionAck,
   * maybe newReceive
   */
  private final case class ProcessedCommand(event: Option[AuctionEvt], ack: AuctionAck, newReceive: Option[Receive])


  /**
   * Updates Auction state
   */
  private def updateState(evt: AuctionEvt, state:Auction): Auction = {

    evt match {
      case AuctionEndedEvt(auctionId: String, timeStamp) =>
        state.copy(ended = true)

      case BidPlacedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) =>
        state.copy(acceptedBids = Bid(bidPrice, buyer, timeStamp) :: state.acceptedBids)

      case BidRefusedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) =>
        state.copy(refusedBids = Bid(bidPrice, buyer, timeStamp) :: state.refusedBids)

      case BidFailedEvt(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long, error: String) =>
        state.copy(refusedBids = Bid(bidPrice, buyer, timeStamp) :: state.refusedBids)

      case _ => state
    }
  }

  private def getCurrentBid(state: Auction): Double =
    state.acceptedBids match {
      case Bid(p, _, _) :: tail => p
      case _ => state.initialPrice
    }


  /**
   * In an attempt to isolate the effects (write to journal, update state, change receive behaviour),
   * each case of the PartialFunction[Any,Unit]  Receive functions: initial, takingBids call
   * handleProcessedCommand ( sender, processedCommand) by convention
   *
   */
  def handleProcessedCommand(sendr: ActorRef, processedCommand: ProcessedCommand): Unit = {

    processedCommand.event.fold(sender() ! processedCommand.ack) { evt =>
      persist(evt.logDebug("+++++++++++++  evt = " + _.toString)) { persistedEvt =>
        readRegion ! Update(await = true)
        sendr ! processedCommand.ack
        processedCommand.newReceive.fold({})(context.become)
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

        handleProcessedCommand(
          sender(),
          ProcessedCommand(
            Some(AuctionStartedEvt(id, start, end, initialPrice, prodId)),
            StartedAuctionAck(id),
            Some(passivate(takingBids(Auction(id, start, end, initialPrice, Nil, Nil, false))).orElse(unknownCommand))
          )
        )
      }
  }

  def takingBids(state: Auction): Receive = {

    case Tick => // end of auction
      val currentTime = System.currentTimeMillis()
      persist(AuctionEndedEvt(state.auctionId, currentTime)) { evt =>
        readRegion ! Update(await = true)
        context.become(passivate(auctionClosed(updateState(evt,state))).orElse(unknownCommand))
      }


    case a@PlaceBidCmd(id, buyer, bidPrice) => {
      val timestamp = System.currentTimeMillis()

      handleProcessedCommand(sender(),
        if (timestamp < state.endTime && timestamp >= state.startTime) {
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
        } else if (timestamp > state.endTime) {
          // auction expired
          ProcessedCommand(None, AuctionEndedAck(id), None)
        } else {
          ProcessedCommand(None, AuctionNotYetStartedAck(id), None)
        }
      )
    }
  }

  def auctionClosed(state:Auction): Receive = {
    case a: PlaceBidCmd => sender() ! AuctionEndedAck(state.auctionId)
    case a: StartAuctionCmd => sender() ! AuctionEndedAck(state.auctionId)
  }

  /** Used only for recovery */
  private var auctionRecoverStateMaybe: Option[Auction] = None

  def receiveRecover: Receive = {
    case evt:AuctionStartedEvt =>
      auctionRecoverStateMaybe = Some(Auction(evt.auctionId,evt.started,evt.end,evt.initialPrice,Nil,Nil,false))

    case evt: AuctionEvt => {
      auctionRecoverStateMaybe = auctionRecoverStateMaybe.map(state =>
        updateState(evt.logDebug("receiveRecover" + _.toString),state))
    }

    // Once recovery is complete, check the state to become the appropriate behaviour
    case RecoveryCompleted => {
      auctionRecoverStateMaybe.fold[Unit]({}) { auctionState =>
        if (auctionState.logDebug("receiveRecover RecoveryCompleted state: " + _.toString).ended)
          context.become(passivate(auctionClosed(auctionState)).orElse(unknownCommand))
        else {
          launchLifetime(auctionState.endTime)
          context.become(passivate(takingBids(auctionState)).orElse(unknownCommand))
        }
      }
    }

    case SnapshotOffer(_, snapshot) =>
      auctionRecoverStateMaybe = snapshot.asInstanceOf[Option[Auction]].logDebug("recovery from snapshot state:" + _.toString)
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
