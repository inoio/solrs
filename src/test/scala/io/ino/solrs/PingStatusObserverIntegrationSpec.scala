package io.ino.solrs

import java.net.ConnectException
import java.util.concurrent.{TimeUnit, TimeoutException, ExecutionException}

import com.ning.http.client.{AsyncHttpClientConfig, AsyncHttpClient}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.scalatest.concurrent.Eventually._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global

class PingStatusObserverIntegrationSpec extends FunSpec with RunningSolr with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  private implicit val awaitTimeout = 1000 millis
  private implicit val futureFactory = io.ino.solrs.future.ScalaFactory
  private val httpClientTimeout = 300
  private val httpClient = new AsyncHttpClient()

  private lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr/collection1"

  override def beforeEach() {
    enable(solrUrl)
  }

  override def afterEach() {
    // if tomcat got stopped but not shut down, we must (and can) start it again
    if(solrRunner.isStopped) {
      solrRunner.tomcat.start()
    }
    // if tomcat got destroyed, we must use solrRunner.start to start from scratch
    else if(solrRunner.isDestroyed) {
      solrRunner.start
    }
  }

  describe("PingStatusObserver") {

    lazy val solrServers = Seq(SolrServer(solrUrl))
    lazy val pingStatusObserver = new PingStatusObserver(solrServers, new AsyncHttpClient())

    it("should update status base on ping status") {
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      disable(solrUrl)
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Disabled)
    }

    it("should disable server on status != 200") {
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      solrRunner.tomcat.stop()

      eventually {
        pingAction(solrUrl, "status").getStatusCode should be (503)
      }

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Failed)

    }

    it("should disable server on idle connection timeout") {

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      solrRunner.tomcat.getConnector.pause()
      try {

        eventually {
          val thrown = the[ExecutionException] thrownBy pingAction(solrUrl, "status")
          thrown.getCause shouldBe a[TimeoutException]
        }

        val httpClientConfig = new AsyncHttpClientConfig.Builder().setIdleConnectionTimeoutInMs(1).build()
        val pingStatusObserver2 = new PingStatusObserver(solrServers, new AsyncHttpClient(httpClientConfig))

        awaitReady(pingStatusObserver2.checkServerStatus())((httpClientTimeout millis) * 2)
        solrServers(0).status should be(Failed)
      } finally {
        solrRunner.tomcat.getConnector.resume()
      }
    }

    it("should disable server on connection error") {
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      solrRunner.stop()

      eventually {
        val thrown = the [ExecutionException] thrownBy pingAction(solrUrl, "status")
        thrown.getCause shouldBe a [ConnectException]
      }

      // We know that the httpClient will throw a ConnectException, which was not the case with the one
      // used by pingStatusObserver...
      val pingStatusObserver2 = new PingStatusObserver(solrServers, httpClient)

      awaitReady(pingStatusObserver2.checkServerStatus())
      solrServers(0).status should be (Failed)
    }

  }

  private def enable(solrUrl: String) = pingAction(solrUrl, "enable")
  private def disable(solrUrl: String) = pingAction(solrUrl, "disable")
  private def pingAction(solrUrl: String, action: String) =
    httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action").execute().get(httpClientTimeout * 2, TimeUnit.MILLISECONDS)

}
