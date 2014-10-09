package io.ino.solrs

import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class SolrServersSpec extends FunSpec with Matchers with FutureAwaits {

  private implicit val timeout = 1.second

  describe("StaticSolrServers") {
    it("should return consecutive solr servers") {
      val solrServers = Seq(SolrServer("host1"), SolrServer("host2"))
      val cut = new StaticSolrServers(solrServers)

      val iter = cut.iterator

      iter.next() should be (solrServers(0))
      iter.next() should be (solrServers(1))
      iter.next() should be (solrServers(0))
    }
  }

  describe("ReloadingSolrServers") {
    it("should return iterator that reflects updated solr servers") {

      def parse(data: Array[Byte]): Seq[SolrServer] = {
        new String(data).split(",").map(SolrServer(_))
      }

      var data = "host1,host2"

      val cut = new ReloadingSolrServers("http://some.url", parse, null) {
        override def loadUrl() = Future.successful(data.getBytes)
      }
      cut.all should have size (0)
      val iterator = cut.iterator
      iterator.hasNext should be (false)

      await(cut.reload())

      cut.all should have size (2)
      iterator.hasNext should be (true)
      iterator.next should be (SolrServer("host2"))
      iterator.next should be (SolrServer("host1"))
      iterator.next should be (SolrServer("host2"))
      iterator.next should be (SolrServer("host1"))


    }
  }
}
