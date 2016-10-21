package com.boldradius.cqrs

import akka.NotUsed
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.pattern.PipeToSupport
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
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
  private case class RecoverComplete(lastSequenceNr: Long, state: Option[BidState])

  context.system.scheduler.scheduleOnce(0 seconds, self, Recover)

  /**
    * Rebuild BidView auction state by loading all the current events received from the BidProcessor.  When complete,
    * send RecoverComplete to self.
    */
  private def recoverEvents(): Unit = {
    /** Route events to BidView to rebuild event state **/
    val source: Source[EventEnvelope, NotUsed] =
      readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr = 0, toSequenceNr = Long.MaxValue)

    source.
      fold((0L, None: Option[BidState])) {
        case ((sequenceNr, state: Option[BidState]), eventEnvelope) =>
          log.info(s"Recovering event: $eventEnvelope")
          val newState = handleProcessorEvent(eventEnvelope.event, state)
          (eventEnvelope.sequenceNr, Option(newState))
      }.
      runWith(Sink.lastOption).
      map {
        case Some((lastSequenceNr, state)) => RecoverComplete(lastSequenceNr, state)
        case _ => RecoverComplete(0, None)
      }.pipeTo(self)
  }

  /**
    * Keep BidView bid state up-to-date by streaming all new events received from the BidProcessor.
    * @param lastSequenceNr The sequence number of the last event processed by recoverEvents.
    * @param state The current bid state derived from recoverEvents.
    */
  private def streamEvents(lastSequenceNr: Long, state: Option[BidState]): Unit = {
    unstashAll()
    updateBehaviour(state)
    log.info(s"Recover done, lastSequenceNr: $lastSequenceNr")
    val liveSource: Source[EventEnvelope, NotUsed] =
      readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr = lastSequenceNr + 1, toSequenceNr = Long.MaxValue)
    liveSource.
      map { eventEnvelope =>
        log.info(s"Received new event: $eventEnvelope")
        val newState = handleProcessorEvent(eventEnvelope.event, state)
        updateBehaviour(Option(newState))
      }.runWith(Sink.ignore)
  }

  /**
    * Create a new bid state based on events from the BidProcessor.
    * @param event The event from BidProcessor.
    * @param state An optional state.  No state will exist if no events have been received from BidProcessor.
    * @return The new bid state.
    */
  private def handleProcessorEvent(event: Any, state: Option[BidState]): BidState = (event, state) match {
      case (AuctionStartedEvt(auctionId, started, end, initialPrice, prodId), None) =>
        BidState(auctionId, started, end, initialPrice, prodId)
      case (e: AuctionEndedEvt, Some(s: BidState)) if !s.closed =>
        s.copy(closed = true)
      case (BidPlacedEvt(auctionId, buyer, bidPrice, timeStamp), Some(s: BidState)) if !s.closed =>
        s.copy(acceptedBids = Bid(bidPrice, buyer, timeStamp) :: s.acceptedBids)
      case (BidRefusedEvt(auctionId, buyer, bidPrice, timeStamp), Some(s: BidState)) if !s.closed =>
        s.copy(rejectedBids = Bid(bidPrice, buyer, timeStamp) :: s.rejectedBids)
      case (_, Some(s)) => s
    }

  /**
    * Become the correct query behaviour given the current bid state.
    * @param state The current bid state.
    */
  private def updateBehaviour(state: Option[BidState]): Unit = state match {
    case Some(s) if s.closed => context.become(passivate(auctionEnded(s)))
    case Some(s) => context.become(passivate(auctionInProgress(s)))
    case _ => context.become(passivate(auctionNotStarted))
  }

  def receive: Receive = passivate(initial)

  /**
    * This is the initial receive method
    *
    * It will only process Recover commands and stash all bid queries until RecoverComplete.
    */
  def initial: Receive = {
    case Recover => recoverEvents()
    case RecoverComplete(lastSequenceNr, state) => streamEvents(lastSequenceNr, state)
    case Status.Failure(t) =>
      log.error(t, "Could not recover events")

    case _: BidQuery =>
      log.info(s"Recover not complete. Stashing event from: $sender")
      stash()
  }

  /**
    * Auction not yet started
    * @return
    */
  def auctionNotStarted: Receive = {
    case WinningBidPriceQuery(auctionId) =>
      sender ! AuctionNotStarted(auctionId)
  }

  /**
   * Responds to all started auction queries
   */
  def auctionInProgress(currentState:BidState):Receive = {
    case GetProdIdQuery(auctionId) =>
      sender ! ProdIdResponse(auctionId,currentState.prodId)

    case GetBidHistoryQuery(auctionId) =>
      sender ! BidHistoryResponse(auctionId,currentState.acceptedBids)

    case WinningBidPriceQuery(auctionId) =>
      currentState.acceptedBids.headOption.fold(
        sender ! WinningBidPriceResponse(auctionId,currentState.product))(b =>
        sender ! WinningBidPriceResponse(auctionId,b.price))
  }

  /**
    * Do nothing once auction has ended.
    */
  def auctionEnded(currentState:BidState):Receive = {
    case _ =>
  }
}
