package io.ino.solrs

import org.apache.http.HeaderElement

/**
 * Created by magro on 4/29/14.
 */
private[solrs] object HttpUtils {
  private val ContentTypePattern = "([a-z]+/[a-z]+)(?:;\\s*charset=([^;]+))?".r

  def getContentCharSet(contentType: String): Option[String] = {
    if (contentType != null) {
      // e.g. application/xml; charset=UTF-8
      contentType match {
        case ContentTypePattern(_, charset) => Some(charset)
        case _ => None
      }
    } else {
      None
    }
  }

  def getMimeType(contentType: String): Option[String] = {
    if (contentType != null) {
      contentType match {
        case ContentTypePattern(mimeType, _) => Some(mimeType)
        case _ => None
      }
    } else {
      None
    }
  }
}
