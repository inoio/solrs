package io.ino.solrs

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.request.CollectionAdminRequest
import org.scalatest.Assertion
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
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
//noinspection RedundantDefaultArgument
class AsyncSolrClientCloudIntegrationSpec extends StandardFunSpec with Eventually with IntegrationPatience {

  private implicit val timeout: FiniteDuration = 5.second

  private var solrRunner: SolrCloudRunner = _
  private def solrServerUrls = solrRunner.solrCoreUrls

  private var solrServers: CloudSolrServers[Future] = _
  private var cut: AsyncSolrClient[Future] = _
  private var solrJClient: CloudSolrClient = _

  private val collection1 = "collection1"
  private val collection2 = "collection2"

  private val q = new SolrQuery("*:*").setRows(1000)

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  override def beforeAll() {
    solrRunner = SolrCloudRunner.start(
      numServers = 2,
      collections = List(
        SolrCollection(collection1, replicas = 2, shards = 1),
        SolrCollection(collection2, replicas = 2, shards = 1)
      ),
      defaultCollection = Some(collection1)
    )
    solrJClient = solrRunner.solrJClient

    solrServers = new CloudSolrServers(
      solrRunner.zkAddress,
      clusterStateUpdateInterval = 100 millis,
      defaultCollection = Some(collection1))
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
      Thread.sleep(500)

      // Restart solr
      SolrRunner.startJetty(solrRunner.jettySolrRunners.last)

      // Wait some time after Jetty was restarted
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
      Thread.sleep(1000)

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

    it("should handle a standard (non-routed) collection alias") {

      awaitAllServersBeingEnabled()

      val otherDocs = manyDocs.filterNot(someDocs.contains).take(42)
      import scala.collection.JavaConverters.seqAsJavaListConverter
      solrJClient.add(collection2, otherDocs.asJava)
      solrJClient.commit(collection2)

      val alias = "testalias"
      val aliasCombined = "testaliascombined"
      CollectionAdminRequest.createAlias(alias, collection1).process(solrJClient)
      CollectionAdminRequest.createAlias(aliasCombined, collection1 + "," + collection2).process(solrJClient)

      // ensure that the alias has been registered// ensure that the aliases have been registered
      var aliases = new CollectionAdminRequest.ListAliases().process(solrJClient).getAliases
      aliases.get(alias) shouldBe collection1
      aliases.get(aliasCombined) shouldBe collection1 + "," + collection2

      eventually {
        await(cut.query(collection1, q).map(getIds)) should contain theSameElementsAs someDocsIds
        await(cut.query(alias, q).map(getIds)) should contain theSameElementsAs someDocsIds
      }

      // switch alias to collection2 and test...
      CollectionAdminRequest.createAlias(alias, collection2).process(solrJClient)

      val otherDocsIds = otherDocs.map(_.getFieldValue("id").toString)
      eventually {
        await(cut.query(collection2, q).map(getIds)) should contain theSameElementsAs otherDocsIds
        await(cut.query(alias, q).map(getIds)) should contain theSameElementsAs otherDocsIds
      }

      // verify that combined alias returns docs from all target collections
      someDocsIds ::: otherDocsIds should not contain theSameElementsAs (someDocsIds)
      await(cut.query(aliasCombined, q).map(getIds)) should contain theSameElementsAs (someDocsIds ::: otherDocsIds)

    }

  }

  private def awaitAllServersBeingEnabled(): Assertion = {
    eventually {
      cut.loadBalancer.solrServers.all.map(_.status) should contain theSameElementsAs solrServerUrls.map(_ => Enabled)
    }
  }

  @tailrec
  private def runQueries(q: SolrQuery, run: AtomicBoolean, res: List[Future[List[String]]] = Nil): List[Future[List[String]]] = if (run.get()) {
    val response = cut.query(q).map(getIds)
    response.failed.foreach {
      case NonFatal(e) => logger.error("Query failed.", e)
    }
    runQueries(q, run, awaitReady(response) :: res)
  } else {
    res
  }

}
