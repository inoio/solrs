package io.ino.solrs

import org.apache.solr.client.solrj.request.{CollectionAdminRequest, GenericSolrRequest}
import org.apache.solr.client.solrj.{SolrQuery, SolrRequest}
import org.scalatest.{FunSpec, Matchers}

class LoadBalancerSpec extends FunSpec with Matchers {
  describe("SingleServerLB") {
    it("should be able to identify admin request types") {
      val adminRequest = CollectionAdminRequest.createAlias("sampleAlias", "sampleCollection")
      val lb = new SingleServerLB(SolrServer("http://solr1:8983/solr/collectionName"))
      lb.solrServer(adminRequest).get shouldBe SolrServer("http://solr1:8983/solr")
    }
  }
}
