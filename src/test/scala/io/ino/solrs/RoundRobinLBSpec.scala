package io.ino.solrs

import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FunSpec}

import scala.concurrent.Future

class RoundRobinLBSpec extends FunSpec with Matchers with FutureAwaits with MockitoSugar {

  describe("RoundRobinLB") {

    it("should return consecutive solr servers") {
      val cut = RoundRobinLB(Seq("host1", "host2"))

      cut.solrServer() should be (SolrServer("host1"))
      cut.solrServer() should be (SolrServer("host2"))
      cut.solrServer() should be (SolrServer("host1"))
    }
  }
}
