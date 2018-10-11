package io.ino.solrs

import org.apache.solr.client.solrj.{SolrQuery, SolrRequest}
import org.apache.solr.client.solrj.request.{CollectionAdminRequest, GenericSolrRequest, QueryRequest}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success

class SolrServersSpec extends FunSpec with Matchers with FutureAwaits {

  private val q = new QueryRequest(new SolrQuery("foo"))
  private implicit val timeout = 1.second

  describe("StaticSolrServers") {
    it("should return consecutive solr servers") {
      val solrServers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = new StaticSolrServers(solrServers)

      val found = cut.matching(q).get

      found should contain theSameElementsAs solrServers
    }

    it("should deliver base url for admin requests") {
      val solrServers = IndexedSeq(SolrServer("http://solr1:8983/solr/testCollection1"), SolrServer("http://solr2:8983/solr/testCollection2"))
      val staticServerSpec = new StaticSolrServers(solrServers)

      val queryRequest = new GenericSolrRequest(SolrRequest.METHOD.POST, "sampleUrl", new SolrQuery())
      val adminRequest = CollectionAdminRequest.createAlias("sampleAlias", "sampleCollection")

      staticServerSpec.matching(queryRequest) shouldBe Success(solrServers)
      staticServerSpec.matching(adminRequest) shouldBe Success(IndexedSeq(SolrServer("http://solr1:8983/solr"), SolrServer("http://solr2:8983/solr")))
    }
  }

  describe("ReloadingSolrServers") {
    it("should return iterator that reflects updated solr servers") {

      def parse(data: Array[Byte]): IndexedSeq[SolrServer] = {
        new String(data).split(",").map(SolrServer(_))
      }

      var data = "host1,host2"

      val cut = new ReloadingSolrServers[Future]("http://some.url", parse, null) {
        override def loadUrl() = {
          val promise = io.ino.solrs.future.ScalaFutureFactory.newPromise[Array[Byte]]
          promise.success(data.getBytes)
          promise.future
        }
      }
      cut.all should have size (0)
      val iterator = cut.matching(q).get
      iterator should have size 0

      await(cut.reload())

      cut.all should have size (2)
      cut.matching(q).get should contain theSameElementsAs Seq(SolrServer("host1"), SolrServer("host2"))

    }

    it("should deliver base url for admin requests") {
      val solrServers = IndexedSeq(SolrServer("http://solr1:8983/solr/testCollection1"), SolrServer("http://solr2:8983/solr/testCollection2"))

      def parse(data: Array[Byte]): IndexedSeq[SolrServer] = {
        new String(data).split(",").map(SolrServer(_))
      }

      val reloadingServers =
        new ReloadingSolrServers[Future]("http://some.url", parse, null) {
          override def loadUrl() = {
            val promise = io.ino.solrs.future.ScalaFutureFactory.newPromise[Array[Byte]]
            promise.success("http://solr1:8983/solr/testCollection1,http://solr2:8983/solr/testCollection2".getBytes)
            promise.future
          }
      }
      await(reloadingServers.reload())

      val queryRequest = new GenericSolrRequest(SolrRequest.METHOD.POST, "sampleUrl", new SolrQuery())
      val adminRequest = CollectionAdminRequest.createAlias("sampleAlias", "sampleCollection")

      reloadingServers.matching(queryRequest).get.toIndexedSeq shouldBe solrServers
      reloadingServers.matching(adminRequest).get.toIndexedSeq shouldBe IndexedSeq(SolrServer("http://solr1:8983/solr"), SolrServer("http://solr2:8983/solr"))
    }

  }
}
