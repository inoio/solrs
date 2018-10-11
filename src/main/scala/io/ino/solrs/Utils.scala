package io.ino.solrs

import java.net.URL

import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.request.CollectionAdminRequest
import org.apache.solr.client.solrj.response.CollectionAdminResponse

import scala.util.Failure
import scala.util.Success
import scala.util.Try

object Utils {

  implicit class OptionOps[A](opt: Option[A]) {

    def toTry(msg: String): Try[A] = {
      opt
        .map(Success(_))
        .getOrElse(Failure(new NoSuchElementException(msg)))
    }
  }

  def solrUrl(baseUrl: String): String = {
    val url = new URL(baseUrl)
    url.getProtocol + "://" +  url.getHost + ":" + url.getPort + "/solr"
  }

  private val adminRequestTypes = List(classOf[CollectionAdminRequest[CollectionAdminResponse]])

  def isAdminType(request: SolrRequest[_]): Boolean =
    adminRequestTypes.exists(el => el.isAssignableFrom(request.getClass))
}