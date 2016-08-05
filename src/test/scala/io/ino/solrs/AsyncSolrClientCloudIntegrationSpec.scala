package io.ino.solrs

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

/**
 * Integration test for AsyncSolrClient with CloudSolrServers + RoundRobinLB + RetryPolicy.TryAvailableServers.
 * RetryPolicy.TryAvailableServers is needed because for a restarted server the status is not updated
 * fast enough.
 */
class AsyncSolrClientCloudIntegrationSpec extends StandardFunSpec with Eventually with IntegrationPatience {

  private implicit val timeout = 5.second

  private var zk: TestingServer = _
  private var solrRunners = List.empty[SolrRunner]

  private var solrServers: CloudSolrServers[Future] = _
  private var cut: AsyncSolrClient[Future] = _
  private var cloudSolrServer: CloudSolrClient = _

  private val q = new SolrQuery("*:*").setRows(Int.MaxValue)

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  override def beforeAll(configMap: ConfigMap) {
    zk = new TestingServer()
    zk.start()

    solrRunners = List(
      SolrRunner.start(18888, Some(ZooKeeperOptions(zk.getConnectString, bootstrapConfig = Some("collection1")))),
      SolrRunner.start(18889, Some(ZooKeeperOptions(zk.getConnectString)))
    )

    cloudSolrServer = new CloudSolrClient.Builder().withZkHost(zk.getConnectString).build()
    cloudSolrServer.setDefaultCollection("collection1")

    solrServers = new CloudSolrServers(
      zk.getConnectString,
      clusterStateUpdateInterval = 100 millis,
      defaultCollection = Some("collection1"))
    // We need to configure a retry policy as otherwise requests fail because server status is not
    // updated fast enough...
    cut = AsyncSolrClient.Builder(RoundRobinLB(solrServers)).withRetryPolicy(RetryPolicy.TryAvailableServers).build

    eventually(Timeout(10 seconds)) {
      cloudSolrServer.deleteByQuery("*:*")
    }
    cloudSolrServer.commit()

    cloudSolrServer.add(someDocsAsJList)
    cloudSolrServer.commit()

    // Check that indexed data is available
    queryAndCheckResponse()
  }

  private def queryAndCheckResponse(): Unit = {
    eventually(Timeout(2 seconds)) {
      getIds(cloudSolrServer.query(q)) should contain theSameElementsAs someDocsIds
    }
  }

  override def afterAll(configMap: ConfigMap) {
    cloudSolrServer.close()
    cut.shutdown()
    solrServers.shutdown
    solrRunners.foreach(_.stop())
    zk.close()
  }

  override def afterEach() {
    solrRunners.foreach { solrRunner =>
      // if tomcat got stopped but not shut down, we must (and can) start it again
      if(solrRunner.isStopped) {
        solrRunner.tomcat.start()
      }
      // if tomcat got destroyed, we must use solrRunner.start to start from scratch
      else if(solrRunner.isDestroyed) {
        solrRunner.start
      }
    }
  }

  describe("AsyncSolrClient with CloudSolrServers + RoundRobinLB") {

    it("should serve queries while solr server is restarted") {

      eventually {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Run queries in the background
      val run = new AtomicBoolean(true)
      val resultFuture = Future {
        runQueries(q, run)
      }

      // Stop solr
      solrRunners.last.stop()

      // Wait some time after tomcat was stopped
      Thread.sleep(100)

      // Restart solr
      solrRunners.last.start

      // Wait some time after tomcat was restarted
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

      eventually {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }
    }

    it("should serve queries when ZK is not available") {

      eventually {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Run queries in the background
      val run = new AtomicBoolean(true)
      val resultFuture = Future {
        runQueries(q, run)
      }

      // Stop ZK
      zk.restart()

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

      eventually(Timeout(10 seconds)) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }
    }

  }

  private def solrRunnerUrls = solrRunners.map(solrRunner => s"http://$hostName:${solrRunner.port}/solr/collection1/")

  @tailrec
  private def runQueries(q: SolrQuery, run: AtomicBoolean, res: List[Future[List[String]]] = Nil): List[Future[List[String]]] = run.get() match {
    case false => res
    case true =>
      val response = cut.query(q).map(getIds)
      response.onFailure {
        case NonFatal(e) => logger.error("Query failed.", e)
      }
      runQueries(q, run, awaitReady(response) :: res)
  }

}
