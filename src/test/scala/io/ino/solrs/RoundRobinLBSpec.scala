package io.ino.solrs

import io.ino.solrs.Fixtures.shardReplica
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.request.UpdateRequest
import org.apache.solr.common.cloud.Replica
import org.apache.solr.common.cloud.Replica.Type.NRT
import org.apache.solr.common.cloud.Replica.Type.PULL
import org.apache.solr.common.cloud.Replica.Type.TLOG
import org.apache.solr.common.params.ShardParams.SHARDS_PREFERENCE
import org.apache.solr.common.params.ShardParams.SHARDS_PREFERENCE_REPLICA_TYPE
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Failure
import scala.util.Success
import scala.util.Try

//noinspection RedundantDefaultArgument
class RoundRobinLBSpec extends AnyFunSpec with Matchers {

  private val q = new QueryRequest(new SolrQuery("foo"))

  describe("RoundRobinLB") {

    it("should return None if no solr server matches") {
      val nonMatchingServers = new SolrServers {
        override def all: Seq[SolrServer] = Nil
        override def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]] = Success(Vector.empty)
      }
      val cut = RoundRobinLB(nonMatchingServers)

      cut.solrServer(q) shouldBe a[Failure[_]]
    }

    it("should return consecutive solr servers") {
      val cut = RoundRobinLB(IndexedSeq("host1", "host2"))

      cut.solrServer(q) should be (Success(SolrServer("host2")))
      cut.solrServer(q) should be (Success(SolrServer("host1")))
      cut.solrServer(q) should be (Success(SolrServer("host2")))
      cut.solrServer(q) should be (Success(SolrServer("host1")))
    }

    it("should only return active solr servers") {
      val servers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      cut.solrServer(q) should be (Success(SolrServer("host2")))
      cut.solrServer(q) should be (Success(SolrServer("host1")))
      cut.solrServer(q) should be (Success(SolrServer("host2")))

      servers(0).status = Disabled
      cut.solrServer(q) should be (Success(SolrServer("host2")))

      servers(0).status = Enabled
      servers(1).status = Failed
      cut.solrServer(q) should be (Success(SolrServer("host1")))
      cut.solrServer(q) should be (Success(SolrServer("host1")))

      servers(0).status = Disabled
      cut.solrServer(q) shouldBe a[Failure[_]]
    }

    it("should return the active leader for update requests if isUpdatesToLeaders=true and UpdateRequest.sendToLeaders=true") {
      val server1 = shardReplica("host1", isLeader = true)
      val server2 = shardReplica("host2", isLeader = false)
      val servers = IndexedSeq(server1, server2)
      val solrServers = new StaticSolrServers(servers) {
        override def findLeader(servers: Iterable[SolrServer]): Option[ShardReplica] = ShardReplica.findLeader(servers)
      }
      val cut = new RoundRobinLB(solrServers, isUpdatesToLeaders = true)

      val q = new UpdateRequest()
      q.setSendToLeaders(true)

      cut.solrServer(q) should be (Success(server1))
      cut.solrServer(q) should be (Success(server1))
      cut.solrServer(q) should be (Success(server1))

      servers(0).status = Disabled
      cut.solrServer(q) should be (Success(server2))
      cut.solrServer(q) should be (Success(server2))
    }

    it("should round robin update requests if isUpdatesToLeaders=true and UpdateRequest.sendToLeaders=false") {
      val server1 = shardReplica("host1", isLeader = true)
      val server2 = shardReplica("host2", isLeader = false)
      val servers = IndexedSeq(server1, server2)
      val solrServers = new StaticSolrServers(servers) {
        override def findLeader(servers: Iterable[SolrServer]): Option[ShardReplica] = ShardReplica.findLeader(servers)
      }
      val cut = new RoundRobinLB(solrServers, isUpdatesToLeaders = true)

      val q = new UpdateRequest()
      q.setSendToLeaders(false)

      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server1))
      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server1))

      servers(0).status = Disabled
      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server2))
    }

    it("should round robin update requests by default") {
      val server1 = shardReplica("host1", isLeader = true)
      val server2 = shardReplica("host2", isLeader = false)
      val servers = IndexedSeq(server1, server2)
      val solrServers = new StaticSolrServers(servers) {
        override def findLeader(servers: Iterable[SolrServer]): Option[ShardReplica] = ShardReplica.findLeader(servers)
      }
      val cut = new RoundRobinLB(solrServers)

      val q = new UpdateRequest()

      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server1))
      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server1))

      servers(0).status = Disabled
      cut.solrServer(q) should be(Success(server2))
      cut.solrServer(q) should be(Success(server2))
    }

    it("should return replicas matching the given shard preferences / replica type") {
      val server1 = shardReplica("host1", replicaType = NRT)
      val server2 = shardReplica("host2", replicaType = TLOG)
      val server3 = shardReplica("host3", replicaType = PULL)
      val servers = IndexedSeq(server1, server2, server3)
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      // shards.preference=replica.location:local,replica.type:PULL,replica.type:TLOG
      def q(replicaTypes: Replica.Type*) = new QueryRequest(
        new SolrQuery("foo")
          .setParam(
            SHARDS_PREFERENCE,
            "replica.location:local," + // just some noice to test the parsing
            replicaTypes.mkString(s"$SHARDS_PREFERENCE_REPLICA_TYPE:", s",$SHARDS_PREFERENCE_REPLICA_TYPE:", ""
          )))

      // don't test expecing server1, 2, 3 since this would be the default RR result anyways...
      cut.solrServer(q(PULL)) should be (Success(server3))
      cut.solrServer(q(PULL)) should be (Success(server3))
      cut.solrServer(q(TLOG)) should be (Success(server2))
      cut.solrServer(q(TLOG)) should be (Success(server2))
      cut.solrServer(q(NRT)) should be (Success(server1))
      cut.solrServer(q(NRT)) should be (Success(server1))

      cut.solrServer(q(TLOG, PULL)) should (be (Success(server2)) or be (Success(server3)))
      cut.solrServer(q(PULL, TLOG)) should (be (Success(server2)) or be (Success(server3)))
      cut.solrServer(q(NRT, PULL)) should (be (Success(server1)) or be (Success(server3)))
      cut.solrServer(q(PULL, NRT)) should (be (Success(server1)) or be (Success(server3)))

      servers(0).status = Disabled
      cut.solrServer(q(NRT, PULL)) shouldBe Success(server3)
    }

    it("should consider the preferred server if active") {
      val servers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      val preferred = Some(SolrServer("host2"))

      cut.solrServer(q, preferred) should be (Success(preferred.get))
      cut.solrServer(q, preferred) should be (Success(preferred.get))

      servers(1).status = Failed
      cut.solrServer(q, preferred) should be (Success(SolrServer("host1")))
    }
  }

}
