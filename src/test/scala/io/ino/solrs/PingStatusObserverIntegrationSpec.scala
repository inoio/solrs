package io.ino.solrs

import java.util.Arrays.asList

import com.ning.http.client.AsyncHttpClient
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PingStatusObserverIntegrationSpec extends FunSpec with RunningSolr with Matchers with FutureAwaits with MockitoSugar {

  private implicit val timeout = 1.second
  private val httpClient = new AsyncHttpClient()

  describe("PingStatusObserver") {

    lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr"
    lazy val solrServers = Seq(SolrServer(solrUrl))
    lazy val pingStatusObserver = new PingStatusObserver(solrServers, httpClient)

    it("should update status base on ping status") {
      enable(solrUrl)
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      disable(solrUrl)
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Disabled)
    }

  }

  private def enable(solrUrl: String) = setStatus(solrUrl, "enable")
  private def disable(solrUrl: String) = setStatus(solrUrl, "disable")
  private def setStatus(solrUrl: String, action: String) =
    httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action").execute().get()

}
