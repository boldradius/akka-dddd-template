package com.boldradius.auction

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import com.boldradius.cqrs.AuctionCommandQueryProtocol._
import com.boldradius.cqrs._
import com.typesafe.config.ConfigFactory
import org.scalatest._
import spray.http.Uri
import spray.routing._

import scala.concurrent.duration._
import spray.json._
import spray.json.DefaultJsonProtocol
import spray.testkit.ScalatestRouteTest
import com.boldradius.util.MarshallingSupport._

object HttpAuctionServiceRouteSpec{
  import spray.util.Utils

  val (_, akkaPort) = Utils temporaryServerHostnameAndPort()

  val config = ConfigFactory.parseString( s"""
     akka.remote.netty.tcp.port = $akkaPort
     akka.log-dead-letters = off
     akka.log-dead-letters-during-shutdown = off
                                           """)

  val testSystem = ActorSystem("offers-route-spec", config)
}


class HttpAuctionServiceRouteSpec extends FeatureSpecLike
with GivenWhenThen
with ScalatestRouteTest
with MustMatchers
with BeforeAndAfterAll
with HttpAuctionServiceRoute {

  import  HttpAuctionServiceRouteSpec._

  implicit val ec = system.dispatcher

  override protected def createActorSystem(): ActorSystem = testSystem

  def actorRefFactory = testSystem



  val cmdActor:ActorRef = system.actorOf( Props( new Actor {
    def receive: Receive = {
      case StartAuctionCmd(id, start, end, initialPrice,prodId) =>
        sender() ! StartedAuctionAck(id)

      case PlaceBidCmd(id,buyer,bidPrice)=>
        sender ! PlacedBidAck(id,buyer,bidPrice,1)
    }
  }))

  val queryActor:ActorRef = system.actorOf( Props( new Actor {
    def receive: Receive = {
      case WinningBidPriceQuery(id) =>
        sender() ! WinningBidPriceResponse(id,1)
      case GetBidHistoryQuery(id) =>
        sender() ! BidHistoryResponse(id,List(Bid(1,"buyer",1)))
      case GetProdIdQuery(id) =>
        sender() ! ProdIdResponse(id,"1")
    }
  }))


  feature("Good Requests") {
    scenario("post is made to create auction") {
      Given("route is properly formed")
      When("/startAuction is called with POST")

      Post(Uri("/startAuction"),StartAuctionDto("123", "2015-01-20-15:53", "2015-01-20-15:53", 1, "1")) ~> route(cmdActor,queryActor) ~> check {
        //responseAs[Any] must be(Map("action" -> "AuctionStarted", "details" -> Map("auctionId" -> "123")))
        responseAs[AuctionStartedDto] must be(AuctionStartedDto("123","AuctionStartedDto"))
      }
      Then(s"Received POST response: ${AuctionStartedDto("123","AuctionStartedDto")}")
    }

    scenario("post is made to bid") {
      Given("route is properly formed")
      When("/bid is called with POST")
      Post(Uri("/bid"),PlaceBidDto("123", "buyer", 1)) ~> route(cmdActor,queryActor) ~> check {
        responseAs[SuccessfulBidDto] must be(SuccessfulBidDto("123",1 , "1969-12-31-19:00","SuccessfulBidDto"))
      }
      Then(s"Received POST response: ${SuccessfulBidDto("123",1 , "1969-12-31-19:00","SuccessfulBidDto")}")
    }
  }
}
