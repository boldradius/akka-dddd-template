package com.boldradius.cqrs

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object ClusterBoot {

  def boot(proxyOnly:Boolean = false)(system: ActorSystem):(ActorRef,ActorRef) = {
    if (proxyOnly) {
      val view = ClusterSharding(system).startProxy(
        typeName = BidView.shardName,
        role = None,
        extractEntityId = BidView.entityIdExtractor,
        extractShardId = BidView.shardIdExtractor)
      val processor = ClusterSharding(system).startProxy(
        typeName = BidProcessor.shardName,
        role = None,
        extractEntityId = BidProcessor.entityIdExtractor,
        extractShardId = BidProcessor.shardIdExtractor)
      (processor, view)
    } else {
      val settings = ClusterShardingSettings(system)
      val view = ClusterSharding(system).start(
        typeName = BidView.shardName,
        entityProps = BidView.props(),
        settings = settings,
        extractEntityId = BidView.entityIdExtractor,
        extractShardId = BidView.shardIdExtractor)
      val processor = ClusterSharding(system).start(
        typeName = BidProcessor.shardName,
        entityProps = BidProcessor.props(),
        settings = settings,
        extractEntityId = BidProcessor.entityIdExtractor,
        extractShardId = BidProcessor.shardIdExtractor)
      (processor, view)
    }
  }

}
