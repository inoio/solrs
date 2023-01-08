package io.ino.solrs

import java.nio.file.{Files, Paths}
import io.ino.solrs.Fixtures.shardReplica
import org.apache.solr.common.cloud.ClusterState
import org.scalatest._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers


/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersSpec extends AnyFunSpec with Matchers {

  import ServerStateChangeObservable._

  describe("CloudSolrServers") {

    it("should compute state changes correctly") {

      val events = CloudSolrServers.diff(
        oldState = Map(
          "col1" -> Seq(shardReplica("h10", Enabled, isLeader = true)),
          "col2" -> Seq(shardReplica("h20", Enabled, isLeader = true), shardReplica("h21", Enabled, isLeader = false), shardReplica("h22", Disabled, isLeader = false))
        ),
        newState = Map(
          "col2" -> Seq(shardReplica("h21", Enabled, isLeader = true), shardReplica("h22", Enabled, isLeader = false), shardReplica("h23", Enabled, isLeader = false)),
          "col3" -> Seq(shardReplica("h30", Enabled, isLeader = false))
        )
      )

      implicit val solrServerOrd: Ordering[SolrServer] = Ordering[String].on[SolrServer](s => s.baseUrl)

      events should contain theSameElementsAs Seq(
        Removed(shardReplica("h10", Enabled, isLeader = true), "col1"),
        Removed(shardReplica("h20", Enabled, isLeader = true), "col2"),
        StateChanged(shardReplica("h21", Enabled, isLeader = false), shardReplica("h21", Enabled, isLeader = true), "col2"),
        StateChanged(shardReplica("h22", Disabled, isLeader = false), shardReplica("h22", Enabled, isLeader = false), "col2"),
        Added(shardReplica("h23", Enabled, isLeader = false), "col2"),
        Added(shardReplica("h30", Enabled, isLeader = false), "col3")
      )

    }

    it("should read all servers from ClusterState with multiple shards") {
      import scala.jdk.CollectionConverters._

      val bytes = Files.readAllBytes(Paths.get(this.getClass.getResource("/cluster_status.json").toURI))
      val cs = ClusterState.load(1, bytes, Set("server1:8983_solr").asJava)

      val collectionToServers = CloudSolrServers.getCollections(cs)
      collectionToServers("my-collection").servers should contain allOf(
        shardReplica("http://server1:8983/solr/my-collection_shard1_replica1", Enabled, isLeader = true),
        shardReplica("http://server2:8983/solr/my-collection_shard1_replica2", Enabled, isLeader = false),
        shardReplica("http://server3:8983/solr/my-collection_shard2_replica1", Enabled, isLeader = true),
        shardReplica("http://server4:8983/solr/my-collection_shard2_replica2", Enabled, isLeader = false),
        shardReplica("http://server5:8983/solr/my-collection_shard3_replica1", Enabled, isLeader = true),
        shardReplica("http://server6:8983/solr/my-collection_shard3_replica2", Enabled, isLeader = false))
    }

  }

}
