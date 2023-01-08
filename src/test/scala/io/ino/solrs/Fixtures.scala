package io.ino.solrs

import org.apache.solr.common.cloud.Replica
import org.apache.solr.common.cloud.ZkStateReader

import scala.collection.mutable

object Fixtures {

  def shardReplica(
      baseUrl: String,
      status: ServerStatus = Enabled,
      replicaType: Replica.Type = Replica.Type.NRT,
      isLeader: Boolean = false
    ): ShardReplica = {
    import scala.jdk.CollectionConverters._
    val leaderProps: Map[String, AnyRef] = if(isLeader) Map(ZkStateReader.LEADER_PROP -> true.toString) else Map.empty
    val replicaStatus = status match {
      case Enabled => Replica.State.ACTIVE
      case Disabled => Replica.State.RECOVERING
      case Failed => Replica.State.RECOVERY_FAILED
    }
    val replica = new Replica(baseUrl, (mutable.Map[String, AnyRef](
      ZkStateReader.NODE_NAME_PROP -> s"$baseUrl:node_1",
      ZkStateReader.CORE_NAME_PROP -> "core",
      ZkStateReader.STATE_PROP -> replicaStatus.toString,
      ZkStateReader.REPLICA_TYPE -> replicaType.name()
    ) ++ leaderProps).asJava, "collection", "slice")
    ShardReplica(baseUrl, replica)
  }

}
