package io.ino.solrs

import java.nio.file.{Files, Paths}

import org.apache.solr.common.cloud.ClusterState
import org.scalatest._


/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersSpec extends FunSpec with Matchers {

  import ServerStateChangeObservable._

  describe("CloudSolrServers") {

    it("should compute state changes correctly") {

      val events = CloudSolrServers.diff(
        oldState = Map(
          "col1" -> Seq(SolrServer("h10", Enabled, isLeader = true)),
          "col2" -> Seq(SolrServer("h20", Enabled, isLeader = true), SolrServer("h21", Enabled, isLeader = false), SolrServer("h22", Disabled, isLeader = false))
        ),
        newState = Map(
          "col2" -> Seq(SolrServer("h21", Enabled, isLeader = true), SolrServer("h22", Enabled, isLeader = false), SolrServer("h23", Enabled, isLeader = false)),
          "col3" -> Seq(SolrServer("h30", Enabled, isLeader = false))
        )
      )

      implicit val solrServerOrd: Ordering[SolrServer] = Ordering[String].on[SolrServer](s => s.baseUrl)

      events should contain theSameElementsAs Seq(
        Removed(SolrServer("h10", Enabled, isLeader = true), "col1"),
        Removed(SolrServer("h20", Enabled, isLeader = true), "col2"),
        StateChanged(SolrServer("h21", Enabled, isLeader = false), SolrServer("h21", Enabled, isLeader = true), "col2"),
        StateChanged(SolrServer("h22", Disabled, isLeader = false), SolrServer("h22", Enabled, isLeader = false), "col2"),
        Added(SolrServer("h23", Enabled, isLeader = false), "col2"),
        Added(SolrServer("h30", Enabled, isLeader = false), "col3")
      )

    }

    it("should read all servers from ClusterState with multiple shards") {
      import scala.collection.JavaConverters._

      val bytes = Files.readAllBytes(Paths.get(this.getClass.getResource("/cluster_status.json").toURI))
      val cs = ClusterState.load(1, bytes, Set("server1:8983_solr").asJava)

      val collectionToServers = CloudSolrServers.getCollections(cs)
      collectionToServers("my-collection").servers should contain allOf(
        SolrServer("http://server1:8983/solr/my-collection_shard1_replica1", Enabled, isLeader = true),
        SolrServer("http://server2:8983/solr/my-collection_shard1_replica2", Enabled, isLeader = false),
        SolrServer("http://server3:8983/solr/my-collection_shard2_replica1", Enabled, isLeader = true),
        SolrServer("http://server4:8983/solr/my-collection_shard2_replica2", Enabled, isLeader = false),
        SolrServer("http://server5:8983/solr/my-collection_shard3_replica1", Enabled, isLeader = true),
        SolrServer("http://server6:8983/solr/my-collection_shard3_replica2", Enabled, isLeader = false))
    }

  }

}
