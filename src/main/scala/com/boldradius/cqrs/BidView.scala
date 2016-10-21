package com.boldradius.cqrs

import akka.NotUsed
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.pattern.PipeToSupport
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import com.boldradius.cqrs.AuctionCommandQueryProtocol._
import com.boldradius.cqrs.BidProcessor._
import com.boldradius.util.ALogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


/**
 * This actor is the Query side of CQRS.
 *
 * Each possible query result is represented as a case class (BidQueryResponse)
 *
 * This actor will initialize itself automatically upon startup from the event journal
 * stored by the corresponding PersistentActor (BidProcessor).
 *
 * There are many strategies for keeping this Actor consistent with the Write side,
 * this example uses the Update() method called from the Write side, which will cause
 * unread journal events to be sent to this actor, which, in turn, can update it's internal state.
 *
 * TODO: Clean up recover process by handling events to rebuild/build state internally instead of thru akka.
 */

/**  state required to satisfy queries  */
final case class BidState(auctionId:String,
                    start:Long,
                    end:Long,
                    product:Double,
                    acceptedBids:List[Bid],
                    rejectedBids:List[Bid],
                    closed:Boolean,
                    prodId: String
                    )
object BidState{
  def apply(auctionId:String,start:Long,end:Long,price:Double,prodId:String):BidState =
    BidState(auctionId,start,end,price,Nil,Nil,closed = false,prodId)
}

object BidView {

  def props():Props = Props(classOf[BidView])

  val entityIdExtractor: ShardRegion.ExtractEntityId = {
    case m : AuctionEvt => (m.auctionId,m)
    case m : BidQuery => (m.auctionId,m)
  }

  val shardIdExtractor: ShardRegion.ExtractShardId = {
    case m: AuctionEvt => (math.abs(m.auctionId.hashCode) % 100).toString
    case m: BidQuery => (math.abs(m.auctionId.hashCode) % 100).toString
  }

  val shardName: String = "BidView"
}

/**
 * The Query Actor
 */
class BidView extends Actor with ALogging with Passivation with Stash with PipeToSupport{

  //override val viewId: String = self.path.parent.name + "-" + self.path.name

  /** It is thru this persistenceId that this actor is linked to the PersistentActor's event journal */
  val persistenceId: String = BidProcessor.shardName + "-" + self.path.name

  /** Create a Persistence Query for all events created by BidProcessor
    * TODO: Since BidView is sharded, does it matter if we instantiate a readJournal on each shard or should it be
    * passed in the Props?
    */
  val readJournal = PersistenceQuery(context.system).readJournalFor[CassandraReadJournal]("cassandra-query-journal")

  implicit val mat = ActorMaterializer()

  /** passivate the entity when no activity */
  context.setReceiveTimeout(1 minute)

  private case object Recover
  private case object RecoverComplete
  var recoverComplete: Boolean = false
  var recoverLastSequenceNr: Long = _

  context.system.scheduler.scheduleOnce(0 seconds, self, Recover)

  private def recoverEvents(): Unit = {
    import akka.pattern.ask

    /** Route events to BidView to rebuild event state **/
    val source: Source[EventEnvelope, NotUsed] =
      readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr = 0, toSequenceNr = Long.MaxValue)
    implicit val askTimeout = Timeout(5.seconds)

    source.
      mapAsync(parallelism = 5) { elem =>
        log.info(s"BidView recover.  Sequence: ${elem.sequenceNr}, event: $elem")
        recoverLastSequenceNr = elem.sequenceNr
        (self ? elem.event).mapTo[akka.Done]
      }.
      runWith(Sink.ignore).map(_ => RecoverComplete).pipeTo(self)
  }

  private def streamEvents() = {
    recoverComplete = true
    unstashAll()
    log.info(s"BidView recover done")
    val liveSource: Source[EventEnvelope, NotUsed] =
      readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr = recoverLastSequenceNr + 1, toSequenceNr = Long.MaxValue)
    liveSource.
      map { elem =>
        log.info(s"BidView received new event: $elem")
        self ! elem.event
      }.runWith(Sink.ignore)
  }

  /**
   * This is the initial receive method
   *
   * It will only process the AuctionStartedEvt or reply to the WinningBidPriceQuery
   *
   */
  def receive: Receive = passivate(initial).orElse(unknownCommand)

  def initial: Receive = {
    case Recover => recoverEvents()

    case _: BidQuery if !recoverComplete =>
      log.info(s"BidView, recover not complete.  Stashing event.  sender: $sender")
      stash()

    case AuctionStartedEvt(auctionId, started, end,initialPrice, prodId) =>
      val newState = BidState(auctionId, started, end, initialPrice, prodId)
      context.become(passivate(auctionInProgress(newState)).orElse(unknownCommand))
      sender() ! akka.Done

    case WinningBidPriceQuery(auctionId) =>
      sender ! AuctionNotStarted(auctionId)
  }

  /**
   * Also responds to updates to the event journal (AuctionEndedEvt,BidPlacedEvt,BidRefusedEvt), and
   * updates internal state as well as responding to queries
   */
  def auctionInProgress(currentState:BidState):Receive = {
    case RecoverComplete => streamEvents()
    case Status.Failure(t) =>
      log.error(t, "Could not recover events")

    case  GetProdIdQuery(auctionId) =>
      sender ! ProdIdResponse(auctionId,currentState.prodId)


    case  GetBidHistoryQuery(auctionId) =>
      sender ! BidHistoryResponse(auctionId,currentState.acceptedBids)

    case  WinningBidPriceQuery(auctionId) =>
      log.info(s"BidView, state recovered.  Replying to WinningBidPriceQuery.  sender: $sender")
      currentState.acceptedBids.headOption.fold(
        sender ! WinningBidPriceResponse(auctionId,currentState.product))(b =>
        sender ! WinningBidPriceResponse(auctionId,b.price))

    case e: AuctionEndedEvt  =>
      val newState = updateState(e, currentState)
      context.become(passivate(auctionEnded(newState)))

    case e: BidPlacedEvt =>
      val newState = updateState(e, currentState)
      context.become(passivate(auctionInProgress(newState)))
      sender() ! akka.Done

    case e: BidRefusedEvt =>
      val newState = updateState(e, currentState)
      context.become(passivate(auctionInProgress(newState)))
      sender() ! akka.Done

  }

  def auctionEnded(currentState:BidState):Receive = {
    case _ => {}
  }

  def unknownCommand:Receive = {
    case other  => {
      sender() ! InvalidAuctionAck("","InvalidAuctionAck")
    }
  }

  /**
    * Updates Bid state
    */
  private def updateState(evt: AuctionEvt, state: BidState): BidState = {

    evt match {
      case _: AuctionEndedEvt  =>
        state.copy(closed = true)
      case BidPlacedEvt(auctionId,buyer,bidPrice,timeStamp) =>
        state.copy(acceptedBids = Bid(bidPrice,buyer, timeStamp) :: state.acceptedBids)
      case BidRefusedEvt(auctionId,buyer,bidPrice,timeStamp) =>
        state.copy(rejectedBids = Bid(bidPrice,buyer, timeStamp) :: state.rejectedBids)

      case _ => state
    }
  }
}
