package io.ino.solrs

import java.util.Arrays.asList
import java.util.concurrent.Executors

import org.asynchttpclient.DefaultAsyncHttpClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.SolrResponse
import org.apache.solr.client.solrj.impl.{NoOpResponseParser, XMLResponseParser}
import org.apache.solr.client.solrj.request.{GenericSolrRequest, QueryRequest}
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Matchers._
import org.mockito.Mockito.verify
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.Millis
import org.scalatest.time.Span

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.XML

class AsyncSolrClientIntegrationSpec extends StandardFunSpec with RunningSolr {

  private implicit val patienceConfig = PatienceConfig(
    timeout = scaled(Span(10000, Millis)),
    interval = scaled(Span(20, Millis))
  )

  private implicit val timeout = 1.second
  private val httpClient = new DefaultAsyncHttpClient()

  private lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr/collection1"
  private lazy val solrs = AsyncSolrClient(solrUrl)

  import io.ino.solrs.SolrUtils._

  override def beforeEach(): Unit = {
    eventually(Timeout(10 seconds)) {
      solrJClient.deleteByQuery("*:*")
    }
    val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
    val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
    solrJClient.add(asList(doc1, doc2))
    solrJClient.commit()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    solrs.shutdown()
    httpClient.close()
  }

  describe("Solr") {

    it("should allow to transform the response") {
      val response: Future[List[String]] = solrs.query(new SolrQuery("cat:cat1")).map(getIds)

      await(response) should contain theSameElementsAs Vector("id1", "id2")
    }

    it("should allow to regularly observe the server status") {
      val solrServers = Seq(SolrServer(solrUrl))

      val solr = AsyncSolrClient.Builder(new SingleServerLB(solrUrl)).withServerStateObservation(
        new PingStatusObserver(solrServers, httpClient),
        20 millis,
        Executors.newSingleThreadScheduledExecutor()
      ).build

      enable(solrUrl)
      eventually {
        solrServers(0).status should be (Enabled)
      }

      disable(solrUrl)
      eventually {
        solrServers(0).status should be (Disabled)
      }

      solr.shutdown()
    }

    it("should be built with LoadBalancer") {
      val solr = AsyncSolrClient.Builder(new SingleServerLB(solrUrl)).build
      val response = solr.query(new SolrQuery("cat:cat1"))
      await(response).getResults.getNumFound should be (2)
    }

    it("should allow to set the http client") {

      val solr = AsyncSolrClient.Builder(solrUrl).withHttpClient(new DefaultAsyncHttpClient()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)

      solr.shutdown()
    }

    it("should allow to set the response parser") {

      val solr = AsyncSolrClient.Builder(solrUrl).withResponseParser(new XMLResponseParser()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)

      solr.shutdown()
    }

    it("should allow to override the response parser per request") {

      val solr = AsyncSolrClient.Builder(solrUrl).build

      val request = new GenericSolrRequest(
      SolrRequest.METHOD.POST,
      null,
        new SolrQuery("cat:cat1").add("wt", "xml")
      )
      request.setResponseParser(new NoOpResponseParser)

      val response = solr.execute(request)

      var xml = XML.loadString(await(response).getResponse.get("response").toString)

      (xml \ "result" \ "@numFound").text.toInt should be (2)

      solr.shutdown()
    }

    it("should return failed future on request with bad query") {

      val response: Future[QueryResponse] = solrs.query(new SolrQuery("fieldDoesNotExist:foo"))

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
      (the [RemoteSolrException] thrownBy await(response)).getMessage should include ("undefined field fieldDoesNotExist")
    }

    it("should return failed future on wrong request path") {
      val solr = AsyncSolrClient(s"http://localhost:${solrRunner.port}/")

      val response = solr.query(new SolrQuery("*:*"))

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
      // embedded Jetty returns 404 with text/html response with error message in body
      (the [RemoteSolrException] thrownBy await(response)).getMessage should include ("Expected mime type [] but got [text/html]")

      solr.shutdown()
    }

    it("should gather request time metrics") {
      val metrics = mock[Metrics]
      val solr = AsyncSolrClient.Builder(solrUrl).withMetrics(metrics).build

      await(solr.query(new SolrQuery("*:*")))

      verify(metrics).requestTime(anyLong())

      solr.shutdown()
    }

    it("should allow to intercept requests") {
      var capturedServer: SolrServer = null
      var capturedRequest: SolrRequest[_] = null
      val interceptor = new RequestInterceptor {
        override def interceptRequest[T <: SolrResponse](f: (SolrServer, SolrRequest[_ <: T]) => future.Future[T])
                                                        (solrServer: SolrServer, r: SolrRequest[_ <: T]): future.Future[T] = {
          capturedServer = solrServer
          capturedRequest = r
          f(solrServer, r)
        }
      }
      val solr = AsyncSolrClient.Builder(solrUrl).withRequestInterceptor(interceptor).build

      val q: SolrQuery = new SolrQuery("*:*")
      await(solr.query(q))

      capturedServer should be (SolrServer(solrUrl))
      capturedRequest.asInstanceOf[QueryRequest].getParams should be (q)

      solr.shutdown()
    }

  }

  private def enable(solrUrl: String) = setStatus(solrUrl, "enable", expectedStatus = 200)
  private def disable(solrUrl: String) = setStatus(solrUrl, "disable", expectedStatus = 503)
  @tailrec
  private def setStatus(solrUrl: String, action: String, expectedStatus: Int, attempt: Int = 1): Unit = {
    val response = httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action&wt=xml").execute().get()
    response.getStatusCode shouldBe 200
    val newStatusCode = httpClient.prepareGet(s"$solrUrl/admin/ping?wt=xml").execute().get().getStatusCode
    if(newStatusCode != expectedStatus && attempt > 3) {
      throw new IllegalStateException(s"Could not reach expected status $expectedStatus via action '$action', reached $newStatusCode instead.")
    } else if (newStatusCode != expectedStatus) {
      Thread.sleep(20)
      setStatus(solrUrl, action, expectedStatus, attempt + 1)
    }
  }

}
