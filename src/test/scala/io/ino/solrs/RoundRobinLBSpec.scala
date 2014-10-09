package io.ino.solrs

import org.scalatest.{Matchers, FunSpec}

class RoundRobinLBSpec extends FunSpec with Matchers {

  describe("RoundRobinLB") {

    it("should return consecutive solr servers") {
      val cut = RoundRobinLB(Seq("host1", "host2"))

      cut.solrServer() should be (Some(SolrServer("host1")))
      cut.solrServer() should be (Some(SolrServer("host2")))
      cut.solrServer() should be (Some(SolrServer("host1")))
    }

    it("should only return active solr servers") {
      val servers = Seq(SolrServer("host1"), SolrServer("host2"))
      val cut = new RoundRobinLB(new StaticSolrServers(servers))

      cut.solrServer() should be (Some(SolrServer("host1")))
      cut.solrServer() should be (Some(SolrServer("host2")))

      servers(0).status = Disabled
      cut.solrServer() should be (Some(SolrServer("host2")))

      servers(0).status = Enabled
      servers(1).status = Failed
      cut.solrServer() should be (Some(SolrServer("host1")))
      cut.solrServer() should be (Some(SolrServer("host1")))

      servers(0).status = Disabled
      cut.solrServer() should be (None)
    }
  }
}
