package io.ino.solrs

import java.net.ConnectException
import java.util.concurrent.{TimeUnit, TimeoutException, ExecutionException}

import com.ning.http.client.{AsyncHttpClientConfig, AsyncHttpClient}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.scalatest.concurrent.Eventually._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global

class PingStatusObserverIntegrationSpec extends FunSpec with RunningSolr with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  private implicit val awaitTimeout = 2000 millis
  private val httpClientTimeout = 20
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

  override def afterAll(): Unit = httpClient.close()

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

      eventually(Timeout(awaitTimeout)) {
        try {
          pingAction(solrUrl, "status").getStatusCode should be (503)
        } catch {
          case e: ExecutionException if e.getCause.isInstanceOf[TimeoutException] =>
            // that's fine as well, alternatively to a 503...
        }
      }

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Failed)

    }

    it("should disable server on idle connection timeout") {

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      solrRunner.tomcat.getConnector.pause()
      val httpClientConfig = new AsyncHttpClientConfig.Builder().setIdleConnectionTimeoutInMs(1).build()
      val asyncHttpClient = new AsyncHttpClient(httpClientConfig)
      try {

        eventually {
          val thrown = the[ExecutionException] thrownBy pingAction(solrUrl, "status")
          thrown.getCause shouldBe a[TimeoutException]
        }

        val pingStatusObserver2 = new PingStatusObserver(solrServers, asyncHttpClient)

        awaitReady(pingStatusObserver2.checkServerStatus())
        solrServers(0).status should be(Failed)
      } finally {
        asyncHttpClient.closeAsynchronously()
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

  private def enable(solrUrl: String, timeoutInMillis: Long = 600) = pingAction(solrUrl, "enable", timeoutInMillis)
  private def disable(solrUrl: String) = pingAction(solrUrl, "disable")
  private def pingAction(solrUrl: String, action: String, timeoutInMillis: Long = httpClientTimeout * 2) =
    httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action").execute().get(timeoutInMillis, TimeUnit.MILLISECONDS)

}
