package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import scala.concurrent.duration.Duration

/**
 * Defines the context for a search query initiated by the client.
 * @param q the search query
 * @param preferred the server that the user would like to use for the query
 * @param failedRequests information regarding failed requests
 */
case class QueryContext(q: SolrQuery, preferred: Option[SolrServer] = None, failedRequests: Seq[RequestInfo] = Seq.empty) {

  def failedRequest(server: SolrServer, duration: Duration, e: Throwable): QueryContext =
    copy(failedRequests = failedRequests :+ RequestInfo(server, duration, e))

  def triedServers = failedRequests.map(_.server)

  def hasTriedServer(server: SolrServer): Boolean = failedRequests.exists(_.server == server)

}

/**
 * Information about a failed request.
 */
case class RequestInfo(server: SolrServer, duration: Duration, exception: Throwable)