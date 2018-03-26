package io.ino.solrs

import io.ino.solrs.AsyncSolrClientMocks._
import io.ino.time.Clock
import org.apache.curator.test.TestingServer
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Test that starts uninitialized, there is no ZK and no solr servers started before tests. 
 */
class CloudSolrServersUninitializedIntegrationSpec extends StandardFunSpec {

  private implicit val awaitTimeout = 2 seconds
  private implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(1000, Millis)))

  private var zk: Option[TestingServer] = None
  private var solrRunners = List.empty[SolrRunner]

  private var cut: Option[CloudSolrServers[Future]] = None

  private type AsyncSolrClient = io.ino.solrs.AsyncSolrClient[Future]

  import SolrUtils._

  override def afterEach() {
    cut.foreach(_.shutdown())
    cut = None

    solrRunners.foreach(_.stop())
    solrRunners = List.empty

    zk.foreach(_.close())
    zk = None
  }

  private def solrRunnerUrls = solrRunners.map(solrRunner => s"http://$hostName:${solrRunner.port}/solr/collection1/")

  describe("CloudSolrServers") {

    /**
     * Somehow motivated by
     * SOLR-5359 CloudSolrServer tries to connect to zookeeper forever when ensemble is unavailable
     * while we do NOT have the requirement that connection retries are stopped after connection timeout
     */
    it("should be able to start and stop with unavailable ZK") {
      // Create CUT when there's no ZK available
      cut = Some(new CloudSolrServers("localhost:2181", zkConnectTimeout = 1 second, clusterStateUpdateInterval = 100 millis))
      val asyncSolrClient = mockDoRequest(mock[AsyncSolrClient])(Clock.mutable)
      cut.foreach(_.setAsyncSolrClient(asyncSolrClient))

      // Just see that shutdown doesn't block
      cut.get.shutdown()

    }

    /**
     * See e.g. SOLR-4044 CloudSolrServer early connect problems
     */
    it("should be able to start with unavailable ZK and should be connected as soon as ZK is available") {
      val zkPort = 2181
      val zkConnectString = s"localhost:$zkPort"

      // Create CUT when there's no ZK available. There are also no solr servers started, so that initially the
      // zkStateReader.createClusterStateWatchersAndUpdate will fail as well...
      cut = Some(new CloudSolrServers(zkConnectString, zkConnectTimeout = 1 second, clusterStateUpdateInterval = 100 millis))
      val asyncSolrClient = mockDoRequest(mock[AsyncSolrClient])(Clock.mutable)
      cut.foreach(_.setAsyncSolrClient(asyncSolrClient))

      // Now start ZK
      zk = Some(new TestingServer(zkPort, true))
      // And our solr runners
      solrRunners = List(
        SolrRunner.start(18888, Some(ZooKeeperOptions(zkConnectString, bootstrapConfig = Some("collection1")))),
        SolrRunner.start(18889, Some(ZooKeeperOptions(zkConnectString)))
      )

      eventually(Timeout(20 seconds)) {
        cut.get.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

    }

    // Support collection alias created after ZkStateReader has been constructed


  }

}
