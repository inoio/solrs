package io.ino.solrs

import java.net.ConnectException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}

import javax.servlet._
import javax.servlet.http.HttpServletResponse
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.asynchttpclient.{DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSpec, Matchers}

import scala.concurrent.duration._
import scala.language.postfixOps

class PingStatusObserverIntegrationSpec extends FunSpec with BeforeAndAfterAll with Eventually with IntegrationPatience with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  import PingStatusObserverIntegrationSpec._

  private implicit val awaitTimeout = 2000 millis
  private val httpClientTimeout = 100
  private val httpClient = new DefaultAsyncHttpClient(new DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(httpClientTimeout).build)

  protected var solrRunner: SolrRunner = _
  protected var solrJClient: HttpSolrClient = _

  private lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr/collection1"

  override def beforeAll(): Unit = {
    solrRunner = SolrRunner.startOnce(8889, extraFilters = Map(classOf[DebuggingFilter] -> "*"))
    solrJClient = new HttpSolrClient.Builder(solrUrl).build()
  }

  override def afterAll(): Unit = {
    httpClient.close()
    solrJClient.close()
    solrRunner.stop()
  }

  override def beforeEach() {
    responseDelayMillis.set(0)
    doReturn404.set(false)
    eventually {
      enable(solrUrl).getStatusCode shouldBe 200
    }
  }

  override def afterEach() {
    if (solrRunner.jetty.isStopped) {
      solrRunner.jetty.start()
    }
  }

  describe("PingStatusObserver") {

    lazy val solrServers = Seq(SolrServer(solrUrl))
    lazy val pingStatusObserver = new PingStatusObserver(solrServers, httpClient)

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

      doReturn404.set(true)

      eventually(Timeout(awaitTimeout)) {
        try {
          pingAction(solrUrl, "status").getStatusCode should be (404)
        } catch {
          case e: ExecutionException if e.getCause.isInstanceOf[TimeoutException] =>
            // that's fine as well, alternatively to a 404...
        }
      }

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Failed)

    }

    it("should disable server on read timeout") {

      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      responseDelayMillis.set(5000)
      val httpClientConfig = new DefaultAsyncHttpClientConfig.Builder().setReadTimeout(100).build()
      val asyncHttpClient = new DefaultAsyncHttpClient(httpClientConfig)
      try {

        eventually {
          val thrown = the[ExecutionException] thrownBy pingAction(solrUrl, "status")
          thrown.getCause shouldBe a[TimeoutException]
        }

        val pingStatusObserver2 = new PingStatusObserver(solrServers, asyncHttpClient)

        awaitReady(pingStatusObserver2.checkServerStatus())
        solrServers(0).status should be(Failed)
      } finally {
        asyncHttpClient.close()
      }
    }

    it("should disable server on connection error") {
      await(pingStatusObserver.checkServerStatus())
      solrServers(0).status should be (Enabled)

      SolrRunner.stopJetty(solrRunner.jetty)

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

object PingStatusObserverIntegrationSpec {

  // the global delay for all requests passing the DebuggingFilter
  private val responseDelayMillis = new AtomicLong(0)

  // whether DebuggingFilter should always return 404
  private val doReturn404 = new AtomicBoolean(false)

  class DebuggingFilter extends Filter {

    private val isOn: AtomicBoolean = new AtomicBoolean(false)

    override def init(filterConfig: FilterConfig): Unit = isOn.set(true)

    override def destroy(): Unit = isOn.set(false)

    override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
      if (isOn.get()) {
        if (doReturn404.get()) {
          response.asInstanceOf[HttpServletResponse].sendError(404)
        } else {
          Thread.sleep(responseDelayMillis.get())
          chain.doFilter(request, response)
        }
      }
    }
  }

}
