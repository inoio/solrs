package io.ino.solrs

import org.scalatest.{Matchers, FunSpec}
import scala.concurrent.Future
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import scala.concurrent.duration._
import java.net.ConnectException

class AsyncSolrClientSpec extends FunSpec with Matchers with FutureAwaits {

  private val query = new SolrQuery("*:*")
  private implicit val timeout = 1.second

  describe("Solr") {

    it("should return failed future on connection refused") {

      val solr = new AsyncSolrClient("http://localhost:12345/solr")

      val response: Future[QueryResponse] = solr.query(query)

      awaitReady(response)
      a [ConnectException] should be thrownBy await(response)
    }

  }

}
