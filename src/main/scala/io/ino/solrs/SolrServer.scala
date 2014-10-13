package io.ino.solrs

/**
 * Represents a solr host.
 */
class SolrServer(val baseUrl: String) {

  // Not a case class, because then status would not be included in toString/equals/hashCode

  @volatile
  var status: ServerStatus = Enabled

  override def toString(): String = s"SolrServer($baseUrl, $status)"

  override def equals(other: Any): Boolean = other match {
    case that: SolrServer => status == that.status && baseUrl == that.baseUrl
    case _ => false
  }

  override def hashCode(): Int = status.hashCode() + baseUrl.hashCode
}

object SolrServer {
  def apply(baseUrl: String): SolrServer = new SolrServer(baseUrl)
  def apply(baseUrl: String, status: ServerStatus): SolrServer = {
    val res = SolrServer(baseUrl)
    res.status = status
    res
  }
}

sealed trait ServerStatus
case object Enabled extends ServerStatus
case object Disabled extends ServerStatus
case object Failed extends ServerStatus