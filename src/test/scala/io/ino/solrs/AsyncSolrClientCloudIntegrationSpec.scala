package io.ino.solrs

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.mock.MockitoSugar
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
class AsyncSolrClientCloudIntegrationSpec extends FunSpec with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  private implicit val timeout = 5.second

  private var zk: TestingServer = _
  private var solrRunners = List.empty[SolrRunner]

  private var cut: AsyncSolrClient = _
  private var cloudSolrServer: CloudSolrClient = _

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  override def beforeAll(configMap: ConfigMap) {
    zk = new TestingServer()
    zk.start()

    solrRunners = List(
      SolrRunner.start(18888, Some(ZooKeeperOptions(zk.getConnectString, bootstrapConfig = Some("collection1")))),
      SolrRunner.start(18889, Some(ZooKeeperOptions(zk.getConnectString)))
    )

    cloudSolrServer = new CloudSolrClient(zk.getConnectString)
    cloudSolrServer.setDefaultCollection("collection1")

    val solrServers = new CloudSolrServers(
      zk.getConnectString,
      clusterStateUpdateInterval = 100 millis,
      defaultCollection = Some("collection1"))
    // We need to configure a retry policy as otherwise requests fail because server status is not
    // updated fast enough...
    cut = AsyncSolrClient.Builder(RoundRobinLB(solrServers)).withRetryPolicy(RetryPolicy.TryAvailableServers).build

    cloudSolrServer.deleteByQuery("*:*")
    cloudSolrServer.add(someDocsAsJList)
    cloudSolrServer.commit()
  }

  override def afterAll(configMap: ConfigMap) {
    cloudSolrServer.shutdown()
    cut.shutdown
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

      eventually(Timeout(2 seconds)) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Check normal operation
      val q = queryAndCheckResponse

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

      eventually(Timeout(2 seconds)) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }
    }

    it("should serve queries when ZK is not available") {

      eventually(Timeout(2 seconds)) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Check normal operation
      val q = queryAndCheckResponse

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

      eventually(Timeout(2 seconds)) {
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

  private def queryAndCheckResponse: SolrQuery = {
    val q = new SolrQuery("*:*").setRows(Int.MaxValue)
    val response: Future[List[String]] = cut.query(q).map(getIds)
    await(response) should contain theSameElementsAs someDocsIds
    q
  }

}
