package com.boldradius.cqrs

final case class Bid(price:Double, buyer:String, timeStamp:Long)

final case class Auction(auctionId:String,
                         startTime:Long,
                         endTime:Long,
                         initialPrice:Double,
                         acceptedBids:List[Bid],
                         refusedBids:List[Bid],
                         ended:Boolean)




