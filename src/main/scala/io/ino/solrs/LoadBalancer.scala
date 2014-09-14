package io.ino.solrs

import scala.annotation.tailrec

trait LoadBalancer {

  /**
   * Determines the solr server to use for a new request.
   */
  def solrServer(): Option[SolrServer]

}

class SingleServerLB(val server: SolrServer) extends LoadBalancer {
  def this(baseUrl: String) = this(SolrServer(baseUrl))
  override val solrServer = Some(server)
}

class RoundRobinLB(val solrServers: Seq[SolrServer]) extends LoadBalancer {
  private val ringIterator = Stream.continually(solrServers).flatten.iterator

  @tailrec
  private def findAvailable(round: Int): Option[SolrServer] = {
    val server = ringIterator.next()
    if (server.status == Enabled) {
      Some(server)
    } else if (round == solrServers.length) {
      None
    } else {
      findAvailable(round + 1)
    }
  }

  override def solrServer(): Option[SolrServer] = {
    findAvailable(0)
  }
}
object RoundRobinLB {
  def apply(baseUrls: Seq[String]): RoundRobinLB = new RoundRobinLB(baseUrls.map(SolrServer(_)))
}