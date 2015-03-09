package com.boldradius.cqrs

object AuctionCommandQueryProtocol {

  sealed  trait AuctionMsg {
    val auctionId: String
  }

  sealed trait AuctionCmd extends AuctionMsg

//  case class BootInitCmd(auctionId: String) extends AuctionCmd
  case class StartAuctionCmd(auctionId: String, start: Long, end: Long, initialPrice: Double, prodId: String) extends AuctionCmd
  case class PlaceBidCmd(auctionId: String, buyer: String, bidPrice: Double) extends AuctionCmd

  sealed trait AuctionAck extends AuctionMsg

  case class StartedAuctionAck(auctionId: String) extends AuctionAck
  case class InvalidAuctionAck(auctionId: String, msg: String) extends AuctionAck
  case class PlacedBidAck(auctionId: String, buyer: String, bidPrice: Double, timeStamp: Long) extends AuctionAck
  case class RefusedBidAck(auctionId: String, buyer: String, bidPrice: Double, winningBid: Double) extends AuctionAck
  case class FailedBidAck(auctionId: String, buyer: String, bidPrice: Double, message: String) extends AuctionAck
  case class AuctionEndedAck(auctionId: String) extends AuctionAck
  case class AuctionNotYetStartedAck(auctionId: String) extends AuctionAck

  sealed trait BidQuery extends AuctionMsg

  case class WinningBidPriceQuery(auctionId: String) extends BidQuery
  case class GetBidHistoryQuery(auctionId: String) extends BidQuery
  case class GetAuctionStartEnd(auctionId: String) extends BidQuery
  case class GetProdIdQuery(auctionId: String) extends BidQuery

  sealed trait BidQueryResponse  extends AuctionMsg

  case class InvalidBidQueryReponse(auctionId: String, message: String) extends BidQueryResponse
  case class AuctionNotStarted(auctionId: String) extends BidQueryResponse
  case class WinningBidPriceResponse(auctionId: String, price: Double) extends BidQueryResponse
  case class BidHistoryResponse(auctionId: String, bids: List[Bid]) extends BidQueryResponse
  case class AuctionStartEndResponse(auctionId: String, start: Long, end: Long) extends BidQueryResponse
  case class ProdIdResponse(auctionId: String, prodId: String) extends BidQueryResponse

}

