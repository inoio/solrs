package io.ino.solrs

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.scalatest.Assertion
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Integration test for AsyncSolrClient with CloudSolrServers + RoundRobinLB + RetryPolicy.TryAvailableServers.
 * RetryPolicy.TryAvailableServers is needed because for a restarted server the status is not updated
 * fast enough.
 */
class AsyncSolrClientCloudIntegrationSpec extends StandardFunSpec with Eventually with IntegrationPatience {

  private implicit val timeout: FiniteDuration = 5.second

  private var solrRunner: SolrCloudRunner = _
  private def solrServerUrls = solrRunner.solrCoreUrls

  private var solrServers: CloudSolrServers[Future] = _
  private var cut: AsyncSolrClient[Future] = _
  private var solrJClient: CloudSolrClient = _

  private val q = new SolrQuery("*:*").setRows(Int.MaxValue)

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  override def beforeAll() {
    solrRunner = SolrCloudRunner.start(2, List(SolrCollection("collection1", 2, 1)), Some("collection1"))
    solrJClient = solrRunner.solrJClient

    solrServers = new CloudSolrServers(
      solrRunner.zkAddress,
      clusterStateUpdateInterval = 100 millis,
      defaultCollection = Some("collection1"))
    // We need to configure a retry policy as otherwise requests fail because server status is not
    // updated fast enough...
    cut = AsyncSolrClient.Builder(RoundRobinLB(solrServers)).withRetryPolicy(RetryPolicy.TryAvailableServers).build

    eventually(Timeout(10 seconds)) {
      solrJClient.deleteByQuery("*:*")
    }
    solrJClient.commit()

    solrJClient.add(someDocsAsJList)
    solrJClient.commit()

    // Check that indexed data is available
    queryAndCheckResponse()
  }

  private def queryAndCheckResponse(): Unit = {
    eventually(Timeout(2 seconds)) {
      getIds(solrJClient.query(q)) should contain theSameElementsAs someDocsIds
    }
  }

  override def afterAll() {
    solrJClient.close()
    cut.shutdown()
    solrServers.shutdown()
    solrRunner.shutdown()
  }

  describe("AsyncSolrClient with CloudSolrServers + RoundRobinLB") {

    it("should serve queries while solr server is restarted") {

      awaitAllServersBeingEnabled()

      // Run queries in the background
      val run = new AtomicBoolean(true)
      val resultFuture = Future {
        runQueries(q, run)
      }

      // Stop solr
      SolrRunner.stopJetty(solrRunner.jettySolrRunners.last)

      // Wait some time after Jetty was stopped
      Thread.sleep(100)

      // Restart solr
      SolrRunner.startJetty(solrRunner.jettySolrRunners.last)

      // Wait some time after Jetty was restarted
      Thread.sleep(200)

      // Stop queries
      run.set(false)

      // Assert
      val responseFutures = await(resultFuture)
      responseFutures.length should be > 0

      responseFutures.foreach { response =>
        response.value.get.isSuccess should be (true)
        await(response) should contain theSameElementsAs someDocsIds
      }

      awaitAllServersBeingEnabled()
    }

    it("should serve queries when ZK is not available") {

      awaitAllServersBeingEnabled()

      // Run queries in the background
      val run = new AtomicBoolean(true)
      val resultFuture = Future {
        runQueries(q, run)
      }

      // Stop ZK
      solrRunner.restartZookeeper()

      // Wait some time after ZK was stopped
      Thread.sleep(500)

      // Stop queries
      run.set(false)

      // Assert
      val responseFutures = await(resultFuture)
      responseFutures.length should be > 0

      responseFutures.foreach { response =>
        response.value.get.isSuccess should be (true)
        await(response) should contain theSameElementsAs someDocsIds
      }

      awaitAllServersBeingEnabled()
    }

  }

  private def awaitAllServersBeingEnabled(): Assertion = {
    eventually {
      cut.loadBalancer.solrServers.all.map(_.status) should contain theSameElementsAs solrServerUrls.map(_ => Enabled)
    }
  }

  @tailrec
  private def runQueries(q: SolrQuery, run: AtomicBoolean, res: List[Future[List[String]]] = Nil): List[Future[List[String]]] = run.get() match {
    case false => res
    case true =>
      val response = cut.query(q).map(getIds)
      response.failed.foreach {
        case NonFatal(e) => logger.error("Query failed.", e)
      }
      runQueries(q, run, awaitReady(response) :: res)
  }

}
