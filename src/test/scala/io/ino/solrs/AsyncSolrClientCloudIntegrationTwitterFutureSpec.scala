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
import io.ino.solrs.future.TwitterFactory
import com.twitter.conversions.time._
import com.twitter.util.{ Future, Promise, Await, Duration }
import scala.language.postfixOps
import scala.util.control.NonFatal

/**
 * Integration test for AsyncSolrClient with CloudSolrServers + RoundRobinLB + RetryPolicy.TryAvailableServers.
 * RetryPolicy.TryAvailableServers is needed because for a restarted server the status is not updated
 * fast enough.
 */
class AsyncSolrClientCloudIntegrationTwitterFutureSpec extends FunSpec with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with MockitoSugar {

  def await[T](future: Future[T])(implicit timeout: Duration): T = Await.result(future, timeout)
  def awaitReady[T](future: Future[T])(implicit timeout: Duration): Future[T] = Await.ready(future, timeout)

  private implicit val timeout = 5.second

  private var zk: TestingServer = _
  private var solrRunners = List.empty[SolrRunner]

  private var cut: AsyncSolrClient[com.twitter.util.Future] = _
  private var cloudSolrServer: CloudSolrClient = _

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  def eventuallyTime = {
    import org.scalatest.time.Span
    import org.scalatest.time.Seconds
    import org.scalatest.concurrent.Timeouts._
    Timeout(Span(2, Seconds))
  }

  def clusterStateUpdateIntervalTime = {
    scala.concurrent.duration.Duration(100, "millis")
  }

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
      clusterStateUpdateInterval = clusterStateUpdateIntervalTime,
      defaultCollection = Some("collection1"))
    // We need to configure a retry policy as otherwise requests fail because server status is not
    // updated fast enough...
    cut = AsyncSolrClient.Builder(RoundRobinLB(solrServers))(TwitterFactory).withRetryPolicy(RetryPolicy.TryAvailableServers).build

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

  describe("AsyncSolrClient with CloudSolrServers + RoundRobinLB Twitter Futures Implementation") {

    it("should serve queries while solr server is restarted") {

      eventually(eventuallyTime) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Stop solr
      solrRunners.last.stop()

      // Wait some time after tomcat was stopped
      Thread.sleep(200)

      // Restart solr
      solrRunners.last.start

      // Wait some time after tomcat was restarted
      Thread.sleep(500)

      // Check normal operation
      val q = queryAndCheckResponse

      // Stop solr
      solrRunners.last.stop()


      eventually(eventuallyTime) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }
    }

    it("should serve queries when ZK is not available") {

      eventually(eventuallyTime) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // Stop solr
      solrRunners.last.stop()

      // Wait some time after tomcat was stopped
      Thread.sleep(200)

      // Restart solr
      solrRunners.last.start

      // Wait some time after tomcat was restarted
      Thread.sleep(500)

      // Check normal operation
      val q = queryAndCheckResponse

      // Stop solr
      solrRunners.last.stop()


      eventually(eventuallyTime) {
        cut.loadBalancer.solrServers.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }
    }

  }

  private def solrRunnerUrls = solrRunners.map(solrRunner => s"http://$hostName:${solrRunner.port}/solr/collection1/")

  private def queryAndCheckResponse: SolrQuery = {
    val q = new SolrQuery("*:*").setRows(Int.MaxValue)
    val response: Future[List[String]] = cut.query(q).map(getIds)
    await(response) should contain theSameElementsAs someDocsIds
    q
  }

}
