package com.boldradius.cqrs

import akka.actor._
import akka.contrib.pattern.ShardRegion
import akka.persistence.PersistentView
import AuctionCommandQueryProtocol._
import com.boldradius.cqrs.BidProcessor._
import com.boldradius.util.Logging
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
 */

/**  state requred to satisfy queries  */
final case class BidState(auctionId:String,
                    start:Long,
                    end:Long,
                    product:Double,
                    acceptedBids:List[Bid],
                    rejectedBids:List[Bid],
                    closed:Boolean)
object BidState{
  def apply(auctionId:String,start:Long,end:Long,price:Double):BidState =
    BidState(auctionId,start,end,price,Nil,Nil,false)
}

object BidView {

  def props():Props = Props(classOf[BidView])

  val idExtractor: ShardRegion.IdExtractor = {
    case m : AuctionEvt => (m.auctionId,m)
    case m : BidQuery => (m.auctionId,m)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case m: AuctionEvt => (math.abs(m.auctionId.hashCode) % 100).toString
    case m: BidQuery => (math.abs(m.auctionId.hashCode) % 100).toString
  }

  val shardName: String = "BidView"

}

/**
 * The Query Actor
 */
class BidView extends PersistentView with Logging with Passivation {

  override val viewId: String = self.path.parent.name + "-" + self.path.name

  /** It is thru this persistenceId that this actor is linked to the PersistentActor's event journal */
  override val persistenceId: String = "BidProcessor" + "-" + self.path.name

  /** passivate the entity when no activity */
  context.setReceiveTimeout(1 minute)

  /**
   * This is the initial receive method
   *
   * It will only process the AuctionStartedEvt or reply to the WinningBidPriceQuery
   *
   */
  def receive: Receive = passivate(initial).orElse(unknownCommand)

  def initial: Receive = {

    case e @ AuctionStartedEvt(auctionId, started, end,intialPrice, prodId) if isPersistent  =>
      val newState = BidState(auctionId,started,end,intialPrice)
      context.become(passivate(auctionInProgress(newState,prodId)).orElse(unknownCommand))

    case  WinningBidPriceQuery(auctionId) =>
      sender ! AuctionNotStarted(auctionId)
  }

  /**
   * Also responds to updates to the event journal (AuctionEndedEvt,BidPlacedEvt,BidRefusedEvt), and
   * updates internal state as well as responding to queries
   */
  def auctionInProgress(currentState:BidState, prodId:String):Receive = {

    case  GetProdIdQuery(auctionId) =>
      sender ! ProdIdResponse(auctionId,prodId)


    case  GetBidHistoryQuery(auctionId) =>
      sender ! BidHistoryResponse(auctionId,currentState.acceptedBids)

    case  WinningBidPriceQuery(auctionId) =>
        currentState.acceptedBids.headOption.fold(
          sender ! WinningBidPriceResponse(auctionId,currentState.product))(b =>
          sender ! WinningBidPriceResponse(auctionId,b.price))

    case e:  AuctionEndedEvt  =>
      val newState =  currentState.copy(closed = true)
      context.become(passivate(auctionEnded(newState)))

    case BidPlacedEvt(auctionId,buyer,bidPrice,timeStamp) if isPersistent =>
        val newState =  currentState.copy(acceptedBids = Bid(bidPrice,buyer, timeStamp) :: currentState.acceptedBids)
        context.become(passivate(auctionInProgress(newState,prodId)))


    case BidRefusedEvt(auctionId,buyer,bidPrice,timeStamp) if isPersistent =>
      val newState =  currentState.copy(rejectedBids = Bid(bidPrice,buyer, timeStamp) :: currentState.rejectedBids)
      context.become(passivate(auctionInProgress(newState,prodId)))

  }

  def auctionEnded(currentState:BidState):Receive = {
    case _ => {}
  }

  def unknownCommand:Receive = {
    case other  => {
      sender() ! InvalidAuctionAck("","InvalidAuctionAck")
    }
  }
}
