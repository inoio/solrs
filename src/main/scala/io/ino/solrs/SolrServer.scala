package io.ino.solrs

/**
 * Represents a solr host.
 *
 * @param baseUrl the solr server's base url, must not end with a slash.
 */
class SolrServer(val baseUrl: String) {

  require(!baseUrl.endsWith("/"), s"baseUrl must not end with '/', but was '$baseUrl'.")

  // Not a case class, because then status would not be included in toString/equals/hashCode

  @volatile
  var status: ServerStatus = Enabled

  def isEnabled: Boolean = status == Enabled

  override def toString: String = s"SolrServer($baseUrl, $status)"

  override def equals(other: Any): Boolean = other match {
    case that: SolrServer => status == that.status && baseUrl == that.baseUrl
    case _ => false
  }

  override def hashCode(): Int = status.hashCode() + baseUrl.hashCode
}

object SolrServer {
  def apply(baseUrl: String): SolrServer = {
    val fixedUrl = if(baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl
    new SolrServer(fixedUrl)
  }
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