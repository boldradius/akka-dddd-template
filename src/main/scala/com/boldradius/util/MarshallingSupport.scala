package com.boldradius.util

import org.json4s.{DefaultFormats, Formats}
import spray.httpx.Json4sSupport

/**
 * Json marshalling for spray.
 */
object MarshallingSupport extends Json4sSupport {
  implicit def json4sFormats: Formats = DefaultFormats
}
