package com.boldradius.cqrs

import akka.actor.{PoisonPill, Actor, ReceiveTimeout}
import com.boldradius.cqrs.AuctionCommandQueryProtocol.InvalidAuctionAck
import com.boldradius.util.Logging
import akka.contrib.pattern.ShardRegion.Passivate

trait Passivation extends Logging {
  this: Actor =>

  protected def passivate(receive: Receive): Receive = receive.orElse{
    // tell parent actor to send us a poisinpill
    case ReceiveTimeout =>
      self.logDebug("ReceiveTimeout: passivating. " + _.toString)
      context.parent ! Passivate(stopMessage = PoisonPill)

    // stop
    case PoisonPill => context.stop(self.logDebug("PoisonPill" + _.toString))
  }
}