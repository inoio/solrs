package io.ino.solrs

import io.ino.solrs.AsyncSolrClientMocks.mockDoRequest
import io.ino.solrs.CloudSolrServers.WarmupQueries
import io.ino.solrs.SolrMatchers.hasQuery
import io.ino.time.Clock
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.embedded.JettySolrRunner
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Matchers.{eq => mockEq, _}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually.{eventually, _}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersIntegrationSpec extends StandardFunSpec {

  private implicit val awaitTimeout: FiniteDuration = 2 seconds
  private implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(20000, Millis)),
                                                       interval = scaled(Span(1000, Millis)))

  private type AsyncSolrClient = io.ino.solrs.AsyncSolrClient[Future]

  private var solrRunner: SolrCloudRunner = _

  private def zkConnectString = solrRunner.zkAddress
  private def solrServerUrls = solrRunner.solrCoreUrls

  private var solrJClient: CloudSolrClient = _
  private var asyncSolrClients: Map[JettySolrRunner, AsyncSolrClient] = _

  private var cut: CloudSolrServers[Future] = _

  import io.ino.solrs.SolrUtils._

  override def beforeAll() {
    // create a 2 node cluster with one collection that has 2 shards with 2 replicas
    solrRunner = SolrCloudRunner.start(
      numServers = 4,
      cores = List(SolrCollection("collection1", replicas = 2, shards = 2)),
      defaultCollection = Some("collection1")
    )
    solrJClient = solrRunner.solrJClient
    asyncSolrClients = solrRunner.jettySolrRunners.map(jetty => jetty -> AsyncSolrClient(s"http://$hostName:${jetty.getLocalPort}/solr/collection1")).toMap

    eventually(Timeout(10 seconds)) {
      solrJClient.deleteByQuery("*:*")
    }
    import scala.collection.JavaConverters._
    solrJClient.add(someDocs.asJava)
    solrJClient.commit()
  }

  override def afterEach(): Unit = {
    cut.shutdown()
  }

  override def afterAll() {
    for (asyncSolrClient <- asyncSolrClients.values) {
      asyncSolrClient.shutdown()
    }
    solrJClient.close()
    solrRunner.shutdown()
  }

  describe("CloudSolrServers") {

    it("should list available solr instances") {
      cut = new CloudSolrServers(zkConnectString, clusterStateUpdateInterval = 100 millis)
      cut.setAsyncSolrClient(mockDoRequest(mock[AsyncSolrClient])(Clock.mutable))

      eventually {
        cut.all should contain theSameElementsAs solrServerUrls.map(SolrServer(_, Enabled))
      }

      asyncSolrClients.foreach { case(_, client) =>
        eventually {
          // don't use Int.MaxValue to get all docs with distributed queries,
          // see also https://stackoverflow.com/questions/32046716/solr-to-get-all-records
          val response = client.query(new SolrQuery("*:*").setRows(1000)).map(getIds)
          await(response) should contain theSameElementsAs someDocsIds
        }
      }
    }

    it("should update available solr instances") {
      cut = new CloudSolrServers(zkConnectString, clusterStateUpdateInterval = 100 millis)
      cut.setAsyncSolrClient(mockDoRequest(mock[AsyncSolrClient])(Clock.mutable))

      val expectedSolrServers = solrServerUrls.map(SolrServer(_, Enabled))
      eventually {
        cut.all should contain theSameElementsAs expectedSolrServers
      }

      SolrRunner.stopJetty(solrRunner.jettySolrRunners.head)
      expectedSolrServers.head.status = Failed
      eventually {
        cut.all should contain theSameElementsAs expectedSolrServers
      }

      SolrRunner.startJetty(solrRunner.jettySolrRunners.head)
      expectedSolrServers.head.status = Enabled
      eventually {
        cut.all should contain theSameElementsAs expectedSolrServers
      }
    }

    it("should test solr instances according to the WarmupQueries") {
      val queries = Seq(new SolrQuery("foo"))
      val warmupQueries = WarmupQueries(queriesByCollection = _ => queries, count = 2)
      cut = new CloudSolrServers(zkConnectString, warmupQueries = Some(warmupQueries))

      val standardResponsePromise = futureFactory.newPromise[QueryResponse]
      val standardResponse = standardResponsePromise.future

      val asyncSolrClient = mockDoRequest(mock[AsyncSolrClient], standardResponse)
      cut.setAsyncSolrClient(asyncSolrClient)

      // initially the list of servers should be empty
      cut.all should be ('empty)

      // as soon as the response is set the LB should provide the servers...
      standardResponsePromise.success(new QueryResponse())
      eventually {
        cut.all should contain theSameElementsAs solrServerUrls.map(SolrServer(_, Enabled))
      }

      // and the servers should have been tested with queries
      solrServerUrls.map(SolrServer(_, Enabled)).foreach { solrServer =>
        warmupQueries.queriesByCollection("col1").foreach { q =>
          verify(asyncSolrClient, times(warmupQueries.count)).doExecute[QueryResponse](mockEq(solrServer), hasQuery(q))(any())
        }
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
