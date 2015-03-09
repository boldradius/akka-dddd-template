package com.boldradius.cqrs

import akka.actor.ActorSystem
import com.boldradius.util.Logging
import com.typesafe.config._

/**
 * Start an akka cluster node
 * Usage:  sbt 'runMain com.boldradius.cqrs.ClusterNodeApp 127.0.0.1 2551'
 */
object ClusterNodeApp extends App {


    val conf =
      """akka.remote.netty.tcp.hostname="%hostname%"
        |akka.remote.netty.tcp.port=%port%
      """.stripMargin

    val argumentsError = """
   Please run the service with the required arguments: <hostIpAddress> <port> """

    assert(args.length == 2, argumentsError)

    val hostname = args(0)
    val port = args(1).toInt
    val config =
      ConfigFactory.parseString( conf.replaceAll("%hostname%",hostname)
        .replaceAll("%port%",port.toString)).withFallback(ConfigFactory.load())

    // Create an Akka system
    implicit val clusterSystem = ActorSystem("ClusterSystem", config)
    ClusterBoot.boot()(clusterSystem)
}
