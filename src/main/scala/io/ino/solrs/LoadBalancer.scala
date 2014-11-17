package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery

import scala.annotation.tailrec

trait LoadBalancer {

  val solrServers: SolrServers


  /**
   * Determines the solr server to use for a new request.
   */
  def solrServer(q: SolrQuery): Option[SolrServer]

}

class SingleServerLB(val server: SolrServer) extends LoadBalancer {
  def this(baseUrl: String) = this(SolrServer(baseUrl))
  private val someServer = Some(server)
  override def solrServer(q: SolrQuery) = someServer
  override val solrServers: SolrServers = new StaticSolrServers(IndexedSeq(server))
}

class RoundRobinLB(override val solrServers: SolrServers) extends LoadBalancer {

  private var idx = 0

  /**
   * Start from the given idx and try servers.length servers to get the next available.
   */
  @tailrec
  private def findAvailable(servers: IndexedSeq[SolrServer], startIndex: Int, round: Int = 0): (Int, Option[SolrServer]) = {
    if (round == servers.length) {
      (startIndex, None)
    } else {
      val server = servers(startIndex)
      if (server.status == Enabled) {
        (startIndex, Some(server))
      } else {
        val nextIndex = (startIndex + 1) % servers.length
        findAvailable(servers, nextIndex, round + 1)
      }
    }
  }

  override def solrServer(q: SolrQuery): Option[SolrServer] = {
    val servers = solrServers.matching(q)
    if(servers.isEmpty) {
      None
    } else {
      // idx + 1 might be > servers.length, so let's use % to get a valid start position
      val startIndex = (idx + 1) % servers.length
      val (newIndex, result) = findAvailable(servers, startIndex)
      idx = newIndex
      result
    }
  }

}
object RoundRobinLB {
  def apply(solrServers: SolrServers): RoundRobinLB = new RoundRobinLB(solrServers)
  def apply(baseUrls: IndexedSeq[String]): RoundRobinLB = new RoundRobinLB(StaticSolrServers(baseUrls))
}