package io.ino.solrs

import io.ino.solrs.SolrServer.fixUrl
import org.apache.solr.common.cloud.Replica
import org.apache.solr.common.cloud.ZkStateReader

final case class SolrServerId(url: String) extends AnyVal

/**
 * Represents a solr host, for a SolrCloud setup there's the specialized [[ShardReplica]].
 *
 * @param baseUrl the solr server's base url, must not end with a slash.
 */
class SolrServer(val baseUrl: String) {

  require(!baseUrl.endsWith("/"), s"baseUrl must not end with '/', but was '$baseUrl'.")

  // Not a case class, because then status would not be included in toString/equals/hashCode

  val id: SolrServerId = SolrServerId(baseUrl)

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

  def apply(baseUrl: String): SolrServer = new SolrServer(fixUrl(baseUrl))

  private[solrs] def fixUrl(baseUrl: String): String = if (baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl

  def apply(baseUrl: String, status: ServerStatus): SolrServer = {
    val res = SolrServer(baseUrl)
    res.status = status
    res
  }
}

class ShardReplica(baseUrl: String, underlying: Replica) extends SolrServer(baseUrl) {

  // according to org.apache.solr.common.cloud.ZkCoreNodeProps.isLeader
  val isLeader: Boolean = underlying.containsKey(ZkStateReader.LEADER_PROP)

  val replicaType: Replica.Type = underlying.getType

  override def toString: String = s"ShardReplica($baseUrl, $status, $replicaType, $isLeader)"

  override def equals(other: Any): Boolean = super.equals(other) && (other match {
    case that: ShardReplica => replicaType == that.replicaType && isLeader == that.isLeader
    case _ => false
  })

  override def hashCode(): Int = replicaType.hashCode() + isLeader.hashCode() + super.hashCode()

}

object ShardReplica {

  def apply(baseUrl: String, underlying: Replica): ShardReplica = {
    val res = new ShardReplica(fixUrl(baseUrl), underlying)
    res.status = underlying.getState match {
      case Replica.State.ACTIVE => Enabled
      case Replica.State.RECOVERING => Disabled
      case Replica.State.RECOVERY_FAILED => Failed
      case Replica.State.DOWN => Failed
    }
    res
  }

  private[solrs] def findLeader(servers: Iterable[SolrServer]): Option[ShardReplica] = servers.collectFirst {
    case s: ShardReplica if s.isLeader => s
  }

}

sealed trait ServerStatus
case object Enabled extends ServerStatus
case object Disabled extends ServerStatus
case object Failed extends ServerStatus