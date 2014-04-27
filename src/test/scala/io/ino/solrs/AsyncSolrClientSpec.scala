package io.ino.solrs

import org.scalatest.{Matchers, FunSpec}
import org.mockito.Mockito._
import scala.Some
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.Nil
import scala.Some
import scala.Some
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import scala.concurrent.duration._
import org.apache.solr.client.solrj.impl.XMLResponseParser
import com.ning.http.client.AsyncHttpClient
import scala.util.{Try, Failure, Success}
import java.util.concurrent.ExecutionException
import java.net.ConnectException

/**
 * Created by magro on 4/26/14.
 */
class AsyncSolrClientSpec extends FunSpec with Matchers with FutureAwaits {

  private val query = new SolrQuery("*:*")
  private implicit val timeout = 1.second

  describe("Solr") {

    it("should return failed future on connection refused") {

      val solr = new AsyncSolrClient("http://localhost:12345/solr",
        new AsyncHttpClient(), new XMLResponseParser())

      val response: Future[QueryResponse] = solr.query(query)

      awaitReady(response)
      a [ConnectException] should be thrownBy await(response)
    }

  }

}
