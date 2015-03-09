package com.boldradius.cqrs

import akka.actor._
import com.boldradius.util.Logging
import spray.routing._

import com.typesafe.config.ConfigFactory
import spray.can.Http
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._


class AuctionHttpActor( command:ActorRef, query:ActorRef )
  extends HttpServiceActor
  with HttpAuctionServiceRoute {
  implicit val ec = context.dispatcher
  def receive = runRoute(route(command,query))
}

/**
 * This spins up the Http server, after connecting to akka cluster.
   * Usage:  sbt 'runMain com.boldradius.auction.cqrs.HttpApp <httpIpAddress>" <httpPort> "<akkaHostIpAddress>" <akkaport>'
 *
 */
object HttpApp extends App{


  private val argumentsError = """
   Please run the service with the required arguments: " <httpIpAddress>" <httpPort> "<akkaHostIpAddress>" <akkaport> """


  val conf =
    """akka.remote.netty.tcp.hostname="%hostname%"
       akka.remote.netty.tcp.port=%port%
    """.stripMargin


  assert(args.length == 4, argumentsError)

  val httpHost = args(0)
  val httpPort = args(1).toInt

  val akkaHostname = args(2)
  val akkaPort = args(3).toInt

  val config =
    ConfigFactory.parseString( conf.replaceAll("%hostname%",akkaHostname)
      .replaceAll("%port%",akkaPort.toString)).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem",config)

  val (processor,view) = ClusterBoot.boot(true)(system)

  val service = system.actorOf( Props( classOf[AuctionHttpActor],processor,view), "cqrs-http-actor")

  implicit val timeout = Timeout(5.seconds)

  IO(Http) ? Http.Bind(service, interface = httpHost, port = httpPort)
}

