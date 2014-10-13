package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.scalatest.{Matchers, FunSpec}

class RoundRobinLBSpec extends FunSpec with Matchers {

  private val q = new SolrQuery("foo")

  describe("RoundRobinLB") {

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
}
