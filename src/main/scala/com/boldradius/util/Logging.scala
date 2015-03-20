package com.boldradius.util

import akka.actor.{Actor, ActorLogging}
import com.typesafe.scalalogging.LazyLogging
import scala.language.implicitConversions

trait ALogging extends ActorLogging{  this: Actor =>

  implicit def toLogging[V](v: V) : FLog[V] = FLog(v)

  case class FLog[V](v : V)  {
    def logInfo(f: V => String): V = {log.info(f(v)); v}
    def logDebug(f: V => String): V = {log.debug(f(v)); v}
    def logError(f: V => String): V = {log.error(f(v)); v}
    def logWarn(f: V => String): V = {log.warning(f(v)); v}
    def logTest(f: V => String): V = {println(f(v)); v}
  }
}
trait LLogging extends LazyLogging{

  implicit def toLogging[V](v: V) : FLog[V] = FLog(v)

  case class FLog[V](v : V)  {
    def logInfo(f: V => String): V = {logger.info(f(v)); v}
    def logDebug(f: V => String): V = {logger.debug(f(v)); v}
    def logError(f: V => String): V = {logger.error(f(v)); v}
    def logWarn(f: V => String): V = {logger.warn(f(v)); v}
    def logTest(f: V => String): V = {println(f(v)); v}
  }
}




