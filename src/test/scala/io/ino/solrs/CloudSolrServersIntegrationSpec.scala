package io.ino.solrs

import io.ino.solrs.CloudSolrServers.WarmupQueries
import io.ino.solrs.SolrMatchers.hasQuery
import io.ino.time.Clock
import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Matchers.{eq => mockEq, _}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersIntegrationSpec extends StandardFunSpec {

  private implicit val awaitTimeout = 2 seconds
  private implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(20000, Millis)),
                                                       interval = scaled(Span(1000, Millis)))

  private type AsyncSolrClient = io.ino.solrs.AsyncSolrClient[Future]

  private var zk: TestingServer = _
  private var solrRunners = List.empty[SolrRunner]
  private var solrs = Map.empty[SolrRunner, AsyncSolrClient]

  import AsyncSolrClientMocks._
  private val asyncSolrClient = mockDoRequest(mock[AsyncSolrClient])(Clock.mutable)

  private var cut: CloudSolrServers[Future] = _
  private var cloudSolrServer: CloudSolrClient = _

  import io.ino.solrs.SolrUtils._

  override def beforeAll() {
    zk = new TestingServer()
    zk.start()

    solrRunners = List(
      SolrRunner.start(18888, Some(ZooKeeperOptions(zk.getConnectString, bootstrapConfig = Some("collection1")))),
      SolrRunner.start(18889, Some(ZooKeeperOptions(zk.getConnectString)))
    )

    solrs = solrRunners.foldLeft(Map.empty[SolrRunner, AsyncSolrClient])( (res, solrRunner) =>
      res + (solrRunner -> AsyncSolrClient(s"http://$hostName:${solrRunner.port}/solr/collection1")))

    cloudSolrServer = new CloudSolrClient.Builder().withZkHost(zk.getConnectString).build()
    cloudSolrServer.setDefaultCollection("collection1")

    cut = new CloudSolrServers(zk.getConnectString, clusterStateUpdateInterval = 100 millis)
    cut.setAsyncSolrClient(asyncSolrClient)

    eventually(Timeout(10 seconds)) {
      cloudSolrServer.deleteByQuery("*:*")
    }
    import scala.collection.JavaConverters._
    cloudSolrServer.add(someDocs.asJava)
    cloudSolrServer.commit()
  }

  override def afterAll() {
    cloudSolrServer.close()
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

    it("should test solr instances according to the WarmupQueries") {
      val queries = Seq(new SolrQuery("foo"))
      val warmupQueries = WarmupQueries(queriesByCollection = _ => queries, count = 2)
      val cut = new CloudSolrServers(zk.getConnectString, warmupQueries = Some(warmupQueries))

      val standardResponsePromise = futureFactory.newPromise[QueryResponse]
      val standardResponse = standardResponsePromise.future

      val asyncSolrClient = mockDoRequest(mock[AsyncSolrClient], standardResponse)
      cut.setAsyncSolrClient(asyncSolrClient)

      // initially the list of servers should be empty
      cut.all should be ('empty)

      // as soon as the response is set the LB should provide the servers...
      standardResponsePromise.success(new QueryResponse())
      eventually {
        cut.all should contain theSameElementsAs solrRunnerUrls.map(SolrServer(_, Enabled))
      }

      // and the servers should have been tested with queries
      solrRunnerUrls.map(SolrServer(_, Enabled)).foreach { solrServer =>
        warmupQueries.queriesByCollection("col1").foreach { q =>
          verify(asyncSolrClient, times(warmupQueries.count)).doExecute[QueryResponse](mockEq(solrServer), hasQuery(q))(any())
        }
      }

      cut.shutdown
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
