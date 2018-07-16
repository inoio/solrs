package io.ino.solrs

import io.ino.solrs.SolrServer.fixUrl
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.common.cloud.Replica
import org.apache.solr.common.cloud.Replica.Type.NRT
import org.apache.solr.common.cloud.Replica.Type.PULL
import org.apache.solr.common.cloud.Replica.Type.TLOG
import org.apache.solr.common.cloud.ZkStateReader
import org.apache.solr.common.params.ShardParams
import org.apache.solr.common.params.ShardParams.SHARDS_PREFERENCE_REPLICA_TYPE

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

  private val replicaTypePattern = s"$SHARDS_PREFERENCE_REPLICA_TYPE:($NRT|$TLOG|$PULL)"r

  private[solrs] def filterByShardPreference(r: SolrRequest[_], servers: IndexedSeq[SolrServer]): IndexedSeq[SolrServer] = {
    if (r.getParams == null || r.getParams.get(ShardParams.SHARDS_PREFERENCE) == null) servers
    else {
      r.getParams.get(ShardParams.SHARDS_PREFERENCE) match {
        // check early if we have ShardReplicas at all, otherwise all the work isn't worth it...
        case preference if preference.contains(SHARDS_PREFERENCE_REPLICA_TYPE) && hasShardReplicas(servers) =>
          val replicaTypes: Set[Replica.Type] = replicaTypePattern.findAllMatchIn(preference).foldLeft(Set.empty[Replica.Type]) { case (res, m) =>
            res + Replica.Type.valueOf(m.group(1))
          }
          val preferredReplicas = servers.collect {
            case s: ShardReplica if replicaTypes.contains(s.replicaType) => s
          }
          if (preferredReplicas.nonEmpty) preferredReplicas else servers
        case _ => servers
      }
    }
  }

  private def hasShardReplicas(servers: IndexedSeq[SolrServer]): Boolean = servers.nonEmpty && servers(0).isInstanceOf[ShardReplica]

}

sealed trait ServerStatus
case object Enabled extends ServerStatus
case object Disabled extends ServerStatus
case object Failed extends ServerStatus