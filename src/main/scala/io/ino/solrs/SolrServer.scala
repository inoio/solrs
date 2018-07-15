package io.ino.solrs

final case class SolrServerId(url: String) extends AnyVal

/**
 * Represents a solr host.
 *
 * @param baseUrl the solr server's base url, must not end with a slash.
 */
class SolrServer(val baseUrl: String, val isLeader: Boolean) {

  require(!baseUrl.endsWith("/"), s"baseUrl must not end with '/', but was '$baseUrl'.")

  // Not a case class, because then status would not be included in toString/equals/hashCode

  val id: SolrServerId = SolrServerId(baseUrl)

  @volatile
  var status: ServerStatus = Enabled

  def withLeader(isLeader: Boolean): SolrServer =
    if(isLeader == this.isLeader) this
    else SolrServer(baseUrl, status, isLeader)

  def isEnabled: Boolean = status == Enabled

  override def toString: String = s"SolrServer($baseUrl, $status, $isLeader)"

  override def equals(other: Any): Boolean = other match {
    case that: SolrServer => isLeader == that.isLeader && status == that.status && baseUrl == that.baseUrl
    case _ => false
  }

  override def hashCode(): Int = isLeader.hashCode() + status.hashCode() + baseUrl.hashCode
}

object SolrServer {

  def apply(baseUrl: String, isLeader: Boolean = false): SolrServer = new SolrServer(fixUrl(baseUrl), isLeader)

  private[solrs] def fixUrl(baseUrl: String): String = if (baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl

  def apply(baseUrl: String, status: ServerStatus, isLeader: Boolean): SolrServer = {
    val res = SolrServer(baseUrl, isLeader)
    res.status = status
    res
  }
}

sealed trait ServerStatus
case object Enabled extends ServerStatus
case object Disabled extends ServerStatus
case object Failed extends ServerStatus