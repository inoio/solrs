package io.ino.solrs

import java.net.ConnectException

import org.asynchttpclient.DefaultAsyncHttpClient
import org.apache.solr.client.solrj.SolrQuery
import org.asynchttpclient.AsyncHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Request
import org.asynchttpclient.Response
import org.mockito.Matchers.any
import org.mockito.Mockito.{doThrow, spy, times, verify}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class AsyncSolrClientSpec extends StandardFunSpec {

  private val query = new SolrQuery("*:*")
  private implicit val timeout = 1.second

  describe("Solr") {

    it("should return failed future on connection refused") {

      val solr = AsyncSolrClient("http://localhost:12345/solr")

      val response = solr.query(query)

      awaitReady(response)
      a [ConnectException] should be thrownBy await(response)
    }

    it("should return failed future on AHC IOException") {
      val ahc = new DefaultAsyncHttpClient()
      val ahcSpy = spy(ahc)
      val solr = AsyncSolrClient.Builder("http://localhost:12345/solr").withHttpClient(ahcSpy).build

      val ex = new RuntimeException("Unexpected?!") with NoStackTrace
      doThrow(ex).when(ahcSpy).executeRequest(any[Request], any[AsyncHandler[Response]])

      val response = solr.query(query)

      awaitReady(response)
      a [RuntimeException] should be thrownBy await(response)

      ahc.close()
    }

    it("should shutdown http client if it was not provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = new AsyncSolrClient.Builder("http://localhost:12345/solr", ascFactory) {
        override def createHttpClient = ahcMock
      }.build

      solr.shutdown()

      verify(ahcMock).close()
    }

    it("should not shutdown http client if it was provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = AsyncSolrClient.Builder("http://localhost:12345/solr").withHttpClient(ahcMock).build

      solr.shutdown()

      verify(ahcMock, times(0)).close()
    }

  }

}
