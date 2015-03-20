package com.boldradius.cqrs

import akka.actor.{PoisonPill, Actor, ReceiveTimeout}
import com.boldradius.util.ALogging
import akka.contrib.pattern.ShardRegion.Passivate

trait Passivation extends ALogging {
  this: Actor =>

  protected def passivate(receive: Receive): Receive = receive.orElse{
    // tell parent actor to send us a poisinpill
    case ReceiveTimeout =>
      self.logInfo( s => s" $s ReceiveTimeout: passivating. ")
      context.parent ! Passivate(stopMessage = PoisonPill)

    // stop
    case PoisonPill => context.stop(self.logInfo( s => s" $s PoisonPill"))
  }
}