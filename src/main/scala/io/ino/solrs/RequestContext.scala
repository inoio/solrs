package io.ino.solrs

import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.SolrResponse

import scala.concurrent.duration.Duration

/**
 * Defines the context for a request initiated by the client.
 * @param r the request
 * @param preferred the server that the user would like to use for the request
 * @param failedRequests information regarding failed requests
 */
case class RequestContext[T <: SolrResponse](r: SolrRequest[_ <: T], preferred: Option[SolrServer] = None, failedRequests: Seq[RequestInfo] = Seq.empty) {

  def failedRequest(server: SolrServer, duration: Duration, e: Throwable): RequestContext[T] =
    copy(failedRequests = failedRequests :+ RequestInfo(server, duration, e))

  def triedServers = failedRequests.map(_.server)

  def hasTriedServer(server: SolrServer): Boolean = failedRequests.exists(_.server == server)

}

/**
 * Information about a failed request.
 */
case class RequestInfo(server: SolrServer, duration: Duration, exception: Throwable)