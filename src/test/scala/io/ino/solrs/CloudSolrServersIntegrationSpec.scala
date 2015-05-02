package io.ino.solrs

import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersIntegrationSpec extends FunSpec with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  private implicit val awaitTimeout = 2 seconds
  private implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(20000, Millis)),
                                                       interval = scaled(Span(1000, Millis)))

  private var zk: TestingServer = _
  private var solrRunners = List.empty[SolrRunner]
  private var solrs = Map.empty[SolrRunner, AsyncSolrClient]

  private var cut: CloudSolrServers = _
  private var cloudSolrServer: CloudSolrClient = _

  import io.ino.solrs.SolrUtils._

  override def beforeAll(configMap: ConfigMap) {
    zk = new TestingServer()
    zk.start()

    solrRunners = List(
      SolrRunner.start(18888, Some(ZooKeeperOptions(zk.getConnectString, bootstrapConfig = Some("collection1")))),
      SolrRunner.start(18889, Some(ZooKeeperOptions(zk.getConnectString)))
    )

    solrs = solrRunners.foldLeft(Map.empty[SolrRunner, AsyncSolrClient])( (res, solrRunner) =>
      res + (solrRunner -> AsyncSolrClient(s"http://$hostName:${solrRunner.port}/solr/collection1")))

    cloudSolrServer = new CloudSolrClient(zk.getConnectString)
    cloudSolrServer.setDefaultCollection("collection1")

    cut = new CloudSolrServers(zk.getConnectString, clusterStateUpdateInterval = 100 millis)

    cloudSolrServer.deleteByQuery("*:*")
    import scala.collection.JavaConversions._
    cloudSolrServer.add(someDocs)
    cloudSolrServer.commit()
  }

  override def afterAll(configMap: ConfigMap) {
    cloudSolrServer.shutdown()
    cut.shutdown
    solrs.values.foreach(_.shutdown())
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

  private def solrRunnerUrls = solrRunners.map(solrRunner => s"http://$hostName:${solrRunner.port}/solr/collection1/")

  describe("CloudSolrServers") {

    it("should list available solr instances") {

      eventually {
        cut.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      solrRunners.foreach { solrRunner =>
        val response = solrs(solrRunner).query(new SolrQuery("*:*").setRows(Int.MaxValue)).map(getIds)
        await(response) should contain theSameElementsAs someDocsIds
      }
    }

    it("should update available solr instances") {

      eventually {
        cut.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      solrRunners.head.stop()
      eventually {
        cut.all.map(_.status) should contain theSameElementsInOrderAs Seq(Failed, Enabled)
      }

      solrRunners.head.start
      eventually {
        cut.all.map(_.status) should contain theSameElementsInOrderAs Seq(Enabled, Enabled)
      }

    }

    it("should resolve server by collection alias") {
      pending
    }

    // SOLR-5359 CloudSolrServer tries to connect to zookeeper forever when ensemble is unavailable
    // + SOLR-4044 CloudSolrServer early connect problems
    //   -> start with zk down, it should recover at some time

    // SOLR-6086 Replica active during Warming
    // -> AsyncSolrClient test: query solr, restart node, all the time docs should be found as expected

    // Support collection alias created after ZkStateReader has been constructed

    // Solrs should serve queries when ZK is not available
    // -> AsyncSolrClient test

  }

}
