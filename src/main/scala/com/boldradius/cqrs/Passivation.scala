package com.boldradius.cqrs

import akka.actor.{Actor, PoisonPill, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import com.boldradius.util.ALogging

trait Passivation extends ALogging {
  this: Actor =>

  protected def passivate(receive: Receive): Receive = receive.orElse{
    // tell parent actor to send us a PoisonPill
    case ReceiveTimeout =>
      self.logInfo( s => s" $s ReceiveTimeout: passivating. ")
      context.parent ! Passivate(stopMessage = PoisonPill)

    // stop
    case PoisonPill => context.stop(self.logInfo( s => s" $s PoisonPill"))
  }
}