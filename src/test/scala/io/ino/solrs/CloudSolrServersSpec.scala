package io.ino.solrs

import java.nio.file.{Files, Paths}

import org.apache.solr.common.cloud.ClusterState
import org.scalatest._

import scala.language.postfixOps

/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersSpec extends FunSpec with Matchers {

  import ServerStateChangeObservable._

  describe("CloudSolrServers") {

    it("should compute state changes correctly") {

      val events = CloudSolrServers.diff(
        oldState = Map(
          "col1" -> Seq(SolrServer("h10", Enabled)),
          "col2" -> Seq(SolrServer("h20", Enabled), SolrServer("h21", Enabled), SolrServer("h22", Disabled))
        ),
        newState = Map(
          "col2" -> Seq(SolrServer("h21", Enabled), SolrServer("h22", Enabled), SolrServer("h23", Enabled)),
          "col3" -> Seq(SolrServer("h30", Enabled))
        )
      )

      implicit val solrServerOrd = Ordering[String].on[SolrServer](s => s.baseUrl)

      events should contain theSameElementsAs Seq(
        Removed(SolrServer("h10", Enabled), "col1"),
        Removed(SolrServer("h20", Enabled), "col2"),
        StateChanged(SolrServer("h22", Disabled), SolrServer("h22", Enabled), "col2"),
        Added(SolrServer("h23", Enabled), "col2"),
        Added(SolrServer("h30", Enabled), "col3")
      )

    }

    it("should read all servers from ClusterState with multiple shards") {
      import scala.collection.JavaConversions._

      val bytes = Files.readAllBytes(Paths.get(this.getClass.getResource("/cluster_status.json").toURI))
      val cs = ClusterState.load(1, bytes, Set("server1:8983_solr"))

      val collectionToServers = CloudSolrServers.getCollectionToServers(cs)
      collectionToServers("my-collection").map(_.baseUrl) should contain allOf(
        "http://server1:8983/solr/my-collection_shard1_replica1",
        "http://server2:8983/solr/my-collection_shard1_replica2",
        "http://server3:8983/solr/my-collection_shard2_replica1",
        "http://server4:8983/solr/my-collection_shard2_replica2",
        "http://server5:8983/solr/my-collection_shard3_replica1",
        "http://server6:8983/solr/my-collection_shard3_replica2")
    }

  }

}
