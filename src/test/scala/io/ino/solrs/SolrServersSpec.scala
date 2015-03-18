package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.scalatest.{FunSpec, Matchers}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import io.ino.solrs.future.ScalaFactory

class SolrServersSpec extends FunSpec with Matchers with FutureAwaits {

  private val q = new SolrQuery("foo")
  private implicit val timeout = 1.second

  describe("StaticSolrServers") {
    it("should return consecutive solr servers") {
      val solrServers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = new StaticSolrServers(solrServers)

      val found = cut.matching(q)

      found should contain theSameElementsAs solrServers
    }
  }

  describe("ReloadingSolrServers") {
    it("should return iterator that reflects updated solr servers") {

      def parse(data: Array[Byte]): IndexedSeq[SolrServer] = {
        new String(data).split(",").map(SolrServer(_))
      }

      var data = "host1,host2"

      val cut = new ReloadingSolrServers("http://some.url", parse, null) {
        override def loadUrl() = {
          val promise = io.ino.solrs.future.ScalaFactory.newPromise[Array[Byte]]
          promise.success(data.getBytes)
          promise.future
        }
      }
      cut.all should have size (0)
      val iterator = cut.matching(q)
      iterator should have size 0

      await(cut.reload())

      cut.all should have size (2)
      cut.matching(q) should contain theSameElementsAs Seq(SolrServer("host1"), SolrServer("host2"))

    }
  }
}
