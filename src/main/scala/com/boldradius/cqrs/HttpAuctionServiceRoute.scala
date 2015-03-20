package com.boldradius.cqrs

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.boldradius.cqrs.AuctionCommandQueryProtocol._
import com.boldradius.util.LLogging
import org.joda.time.format.DateTimeFormat
import spray.routing._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}


final case class PlaceBidDto(auctionId:String, buyer:String, bidPrice:Double )
final case class StartAuctionDto(auctionId:String, start:String, end:String, initialPrice: Double, prodId: String)
final case class BidDto(price:Double, buyer:String, timeStamp:String)

final case class AuctionError(auctionId:String,msg:String,response:String = "AuctionError")
final case class AuctionStartedDto(auctionId:String,response:String = "AuctionStartedDto")
final case class AuctionNotStartedDto(auctionId:String,response:String = "AuctionNotStartedDto")
final case class SuccessfulBidDto(auctionId:String, bidPrice: Double, timeStamp:String,response:String = "SuccessfulBidDto")
final case class RejectedBidDto(auctionId:String, bidPrice: Double, currentBid:Double,response:String = "RejectedBidDto")
final case class FailedBidDto(auctionId:String, bidPrice: Double, currentBid:Double,response:String = "FailedBidDto")
final case class WinningBidDto(auctionId:String,bidPrice: Double,response:String = "WinningBidDto")
final case class BidHistoryDto(auctionId:String,bids: List[BidDto],response:String = "BidHistoryDto")




trait HttpAuctionServiceRoute extends HttpService with LLogging{



  implicit val ec: ExecutionContext

  import com.boldradius.util.MarshallingSupport._


  implicit val timeout = Timeout(30 seconds)
  lazy val fmt = DateTimeFormat.forPattern("yyyy-MM-dd-HH:mm")

  def route(command: ActorRef, query:ActorRef) = {
    post {
      path("startAuction") {
          extract(_.request) { e =>
            entity(as[StartAuctionDto]) {
              auction => onComplete(
                (command ? StartAuctionCmd(auction.auctionId,
                  fmt.parseDateTime(auction.start).getMillis,
                  fmt.parseDateTime(auction.end).getMillis,
                  auction.initialPrice, auction.prodId)).mapTo[AuctionAck]) {
                case Success(ack) => ack match {
                  case StartedAuctionAck(id) =>
                    complete(AuctionStartedDto(id))
                  case InvalidAuctionAck(id, msg) =>
                    complete(AuctionError("ERROR",id, msg))
                  case other =>
                    complete(AuctionError("ERROR",ack.auctionId, ack.toString))
                }
                case Failure(t) =>
                  t.printStackTrace()
                  complete(AuctionError("ERROR",auction.auctionId, t.getMessage))
              }
            }
          }
      } ~
        path("bid") {
          detach(ec) {
            extract(_.request) { e =>
              entity(as[PlaceBidDto]) {
                bid => onComplete(
                  (command ? PlaceBidCmd(bid.auctionId, bid.buyer, bid.bidPrice)).mapTo[AuctionAck]) {
                  case Success(ack) => ack.logInfo(s"PlaceBidCmd bid.bidPrice ${bid.bidPrice} id:" + _.auctionId.toString) match {
                    case PlacedBidAck(id, buyer, bidPrice, timeStamp) =>
                      complete(SuccessfulBidDto(id, bidPrice, fmt.print(timeStamp)))
                    case RefusedBidAck(id, buyer, bidPrice, winningBid) =>
                      complete(RejectedBidDto(id, bidPrice, winningBid))
                    case other =>
                      complete(AuctionError("ERROR",bid.auctionId, other.toString))
                  }
                  case Failure(t) =>
                    complete(AuctionError("ERROR",bid.auctionId, t.getMessage))
                }
              }
            }
          }
        }
    } ~
      get {
        path("winningBid" / Rest) { auctionId =>
          detach(ec) {
            onComplete((query ? WinningBidPriceQuery(auctionId)).mapTo[BidQueryResponse]) {
              case Success(s) => s match {
                case WinningBidPriceResponse(id, price) =>
                  complete(WinningBidDto(id, price))
                case AuctionNotStarted(id) =>
                  complete(AuctionNotStartedDto(id))
                case _ =>
                  complete(AuctionError("ERROR",auctionId, ""))
              }
              case Failure(t) =>
                t.getMessage.logError("WinningBidPriceQuery error: " + _)
                complete(AuctionError("ERROR",auctionId, t.getMessage))
            }
          }
        } ~
          path("bidHistory" / Rest) { auctionId =>
            onComplete((query ? GetBidHistoryQuery(auctionId)).mapTo[BidQueryResponse]) {
              case Success(s) => s match {
                case BidHistoryResponse(id, bids) =>
                  complete(BidHistoryDto(id, bids.map(b =>
                    BidDto(b.price, b.buyer, fmt.print(b.timeStamp)))))
                case AuctionNotStarted(id) =>
                  complete(AuctionNotStartedDto(id))
                case _ =>
                  complete(AuctionError("ERROR",auctionId, ""))
              }
              case Failure(t) =>
                complete(AuctionError("ERROR",auctionId, t.getMessage))
            }
          }
      }
  }
}

