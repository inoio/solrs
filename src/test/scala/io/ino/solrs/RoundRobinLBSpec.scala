package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.request.UpdateRequest
import org.scalatest.FunSpec
import org.scalatest.Matchers

import scala.util.Failure
import scala.util.Success
import scala.util.Try

//noinspection RedundantDefaultArgument
class RoundRobinLBSpec extends FunSpec with Matchers {

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

    it("should return the active leader for update requests") {
      val server1 = SolrServer("host1", isLeader = true)
      val server2 = SolrServer("host2", isLeader = false)
      val servers = IndexedSeq(server1, server2)
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      val q = new UpdateRequest()

      cut.solrServer(q) should be (Success(server1))
      cut.solrServer(q) should be (Success(server1))
      cut.solrServer(q) should be (Success(server1))

      servers(0).status = Disabled
      cut.solrServer(q) should be (Success(server2))
      cut.solrServer(q) should be (Success(server2))
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
