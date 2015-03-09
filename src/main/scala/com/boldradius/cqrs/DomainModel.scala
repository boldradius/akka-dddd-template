package com.boldradius.cqrs

case class Bid(price:Double, buyer:String, timeStamp:Long)

  case class Auction(auctionId:String, start:Long, end:Long, prodId: String, initialPrice: Double)

  case class AuctionBidState(auctionId:String,
                           startTime:Long,
                           endTime:Long,
                           initialPrice:Double,
                           acceptedBids:List[Bid],
                           refusedBids:List[Bid],
                           ended:Boolean)




