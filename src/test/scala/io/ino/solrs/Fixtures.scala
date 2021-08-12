package io.ino.solrs

import org.apache.solr.common.cloud.Replica
import org.apache.solr.common.cloud.ZkStateReader

object Fixtures {

  def shardReplica(
      baseUrl: String,
      status: ServerStatus = Enabled,
      replicaType: Replica.Type = Replica.Type.NRT,
      isLeader: Boolean = false
    ): ShardReplica = {
    import scala.collection.JavaConverters.mapAsJavaMapConverter
    val leaderProps: Map[String, AnyRef] = if(isLeader) Map(ZkStateReader.LEADER_PROP -> true.toString) else Map.empty
    val replicaStatus = status match {
      case Enabled => Replica.State.ACTIVE
      case Disabled => Replica.State.RECOVERING
      case Failed => Replica.State.RECOVERY_FAILED
    }
    val propMap = new java.util.HashMap[String, AnyRef]
    propMap.put(ZkStateReader.NODE_NAME_PROP, "node1:8983_solr")
    propMap.put(ZkStateReader.CORE_NAME_PROP, "core")
    propMap.put(ZkStateReader.STATE_PROP, replicaStatus.toString)
    propMap.put(ZkStateReader.REPLICA_TYPE, replicaType.name())
    leaderProps.foreach(p => propMap.put(p._1, p._2))
    val replica = new Replica(baseUrl, propMap, "collection", "slice")
    ShardReplica(baseUrl, replica)
  }

}
