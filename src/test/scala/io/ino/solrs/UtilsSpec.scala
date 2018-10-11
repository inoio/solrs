package io.ino.solrs

import org.apache.solr.client.solrj.{SolrQuery, SolrRequest}
import org.apache.solr.client.solrj.request.{CollectionAdminRequest, GenericSolrRequest}
import org.scalatest.{FunSpec, Matchers}

class UtilsSpec extends FunSpec with Matchers {
  describe("SolrRequestAnalyzer") {
    it("should be able to identify admin request types") {
      val adminRequest = CollectionAdminRequest.createAlias("sampleAlias", "sampleCollection")
      Utils.isAdminType(adminRequest) shouldBe true

      val queryRequest = new GenericSolrRequest(SolrRequest.METHOD.POST, "sampleUrl", new SolrQuery())
      Utils.isAdminType(queryRequest) shouldBe false
    }
  }
}
