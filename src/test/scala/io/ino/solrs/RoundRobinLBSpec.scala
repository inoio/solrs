package io.ino.solrs

import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.scalatest.{Matchers, FunSpec}

class RoundRobinLBSpec extends FunSpec with Matchers {

  private val q = new QueryRequest(new SolrQuery("foo"))

  describe("RoundRobinLB") {

    it("should return None if no solr server matches") {
      val nonMatchingServers = new SolrServers {
        override def all: Seq[SolrServer] = Nil
        override def matching(r: SolrRequest[_]): IndexedSeq[SolrServer] = Vector.empty
      }
      val cut = RoundRobinLB(nonMatchingServers)

      cut.solrServer(q) should be (None)
    }

    it("should return consecutive solr servers") {
      val cut = RoundRobinLB(IndexedSeq("host1", "host2"))

      cut.solrServer(q) should be (Some(SolrServer("host2")))
      cut.solrServer(q) should be (Some(SolrServer("host1")))
      cut.solrServer(q) should be (Some(SolrServer("host2")))
      cut.solrServer(q) should be (Some(SolrServer("host1")))
    }

    it("should only return active solr servers") {
      val servers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      cut.solrServer(q) should be (Some(SolrServer("host2")))
      cut.solrServer(q) should be (Some(SolrServer("host1")))
      cut.solrServer(q) should be (Some(SolrServer("host2")))

      servers(0).status = Disabled
      cut.solrServer(q) should be (Some(SolrServer("host2")))

      servers(0).status = Enabled
      servers(1).status = Failed
      cut.solrServer(q) should be (Some(SolrServer("host1")))
      cut.solrServer(q) should be (Some(SolrServer("host1")))

      servers(0).status = Disabled
      cut.solrServer(q) should be (None)
    }
  }

  it("should consider the preferred server if active") {
    val servers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
    val cut = new RoundRobinLB(new StaticSolrServers(servers))

    val preferred = Some(SolrServer("host2"))

    cut.solrServer(q, preferred) should be (preferred)
    cut.solrServer(q, preferred) should be (preferred)

    servers(1).status = Failed
    cut.solrServer(q, preferred) should be (Some(SolrServer("host1")))
  }
}
