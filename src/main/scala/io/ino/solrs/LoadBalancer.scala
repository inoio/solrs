package io.ino.solrs

trait LoadBalancer {

  /**
   * Determines the solr server to use for a new request.
   */
  def solrServer(): SolrServer

}

class SingleServerLB(val solrServer: SolrServer) extends LoadBalancer {
  def this(baseUrl: String) = this(SolrServer(baseUrl))
}

class RoundRobinLB(val solrServers: Seq[SolrServer]) extends LoadBalancer {
  private val ringIterator = Stream.continually(solrServers).flatten.iterator
  override def solrServer(): SolrServer = ringIterator.next()
}
object RoundRobinLB {
  def apply(baseUrls: Seq[String]): RoundRobinLB = new RoundRobinLB(baseUrls.map(SolrServer(_)))
}