package io.ino.solrs

import java.io.IOException
import java.net.ConnectException

import com.ning.http.client.AsyncHttpClient
import org.apache.solr.client.solrj.SolrQuery
import org.mockito.Matchers.any
import org.mockito.Mockito.{doThrow, spy, times, verify}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.duration._

class AsyncSolrClientSpec extends FunSpec with Matchers with FutureAwaits with MockitoSugar {

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
      val ahc = new AsyncHttpClient()
      val ahcSpy = spy(ahc)
      val solr = AsyncSolrClient.Builder("http://localhost:12345/solr").withHttpClient(ahcSpy).build

      val ioe = new IOException("Closed") {
        override def fillInStackTrace(): Throwable = this
      }
      doThrow(ioe).when(ahcSpy).executeRequest(any, any)

      val response = solr.query(query)

      awaitReady(response)
      a [IOException] should be thrownBy await(response)

      ahc.closeAsynchronously()
    }

    it("should shutdown http client if it was not provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = new AsyncSolrClient.Builder("http://localhost:12345/solr") {
        override def createHttpClient = ahcMock
      }.build

      solr.shutdown()

      verify(ahcMock).closeAsynchronously()
    }

    it("should not shutdown http client if it was provided") {
      val ahcMock = mock[AsyncHttpClient]
      val solr = AsyncSolrClient.Builder("http://localhost:12345/solr").withHttpClient(ahcMock).build

      solr.shutdown()

      verify(ahcMock, times(0)).closeAsynchronously()
    }

  }

}
