package io.ino.solrs

import com.ning.http.client.AsyncHttpClient
import org.apache.solr.client.solrj.response.QueryResponse
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FunSpec}
import org.mockito.Mockito.{verify, times}
import scala.concurrent.Future
import org.apache.solr.client.solrj.SolrQuery
import java.net.ConnectException
import scala.concurrent.duration._
import io.ino.solrs.future.ScalaFactory

class AsyncSolrClientSpec extends FunSpec with Matchers with FutureAwaits with MockitoSugar {

  private val query = new SolrQuery("*:*")
  private implicit val timeout = 1.second

  describe("Solr") {

    it("should return failed future on connection refused") {

      val solr = AsyncSolrClient("http://localhost:12345/solr")

      val response: Future[QueryResponse] = solr.query(query)

      awaitReady(response)
      a [ConnectException] should be thrownBy await(response)
    }

    it("should shutdown http client if it was not provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = new AsyncSolrClient.Builder("http://localhost:12345/solr")(ScalaFactory) {
        override def createHttpClient = ahcMock
      }.build

      solr.shutdown

      verify(ahcMock).closeAsynchronously()
    }

    it("should not shutdown http client if it was provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = AsyncSolrClient.Builder("http://localhost:12345/solr").withHttpClient(ahcMock).build

      solr.shutdown

      verify(ahcMock, times(0)).closeAsynchronously()
    }

  }

}
