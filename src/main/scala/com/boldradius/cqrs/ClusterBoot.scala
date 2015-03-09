package com.boldradius.cqrs

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.contrib.pattern.ClusterSharding


object ClusterBoot {

  def boot(proxyOnly:Boolean = false)(clusterSystem: ActorSystem):(ActorRef,ActorRef) = {
    val view = ClusterSharding(clusterSystem).start(
      typeName = BidView.shardName,
      entryProps = if(!proxyOnly) Some(BidView.props()) else None,
      idExtractor = BidView.idExtractor,
      shardResolver = BidView.shardResolver)
    val processor = ClusterSharding(clusterSystem).start(
      typeName = BidProcessor.shardName,
      entryProps = if(!proxyOnly) Some(BidProcessor.props(view)) else None,
      idExtractor = BidProcessor.idExtractor,
      shardResolver = BidProcessor.shardResolver)
    (processor,view)
  }

}
