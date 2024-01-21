package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Realm
import org.asynchttpclient.Realm.AuthScheme
import org.scalatest.Assertion
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

//noinspection RedundantDefaultArgument
class AsyncSolrClientBasicAuthCloudIntegrationSpec extends StandardFunSpec with Eventually with IntegrationPatience {

  private implicit val timeout: FiniteDuration = 5.second

  private var solrRunner: SolrCloudRunner = _
  private def solrServerUrls = solrRunner.solrCoreUrls

  private var solrServers: CloudSolrServers[Future] = _
  private var cut: AsyncSolrClient[Future] = _
  private var solrJClient: CloudSolrClient = _

  private val collection1 = "collection1"

  private val q = new SolrQuery("*:*").setRows(1000)

  private val logger = LoggerFactory.getLogger(getClass)

  import io.ino.solrs.SolrUtils._

  override def beforeEach(): Unit = {
    solrRunner = SolrCloudRunner.start(
      numServers = 2,
      collections = List(SolrCollection(collection1, replicas = 2, shards = 1)),
      defaultCollection = Some(collection1),
      // read from src/test/resources, because sbt might not copy the symlink solr/collection1/conf when copying to target...
      maybeSolrHome = Some(Paths.get("./src/test/resources/solr-basic-auth").toAbsolutePath.normalize())
    )
    solrJClient = solrRunner.solrJClient

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
    eventually(Timeout(2 seconds), Interval(50 milliseconds)) {
      getIds(solrJClient.query(q)) should contain theSameElementsAs someDocsIds
    }
  }

  override def afterEach(): Unit = {
    solrJClient.close()
    cut.shutdown()
    solrServers.shutdown()
    solrRunner.shutdown()
  }

  describe("AsyncSolrClient with CloudSolrServers") {

    it("should support basic auth with a configured http client") {

      val httpClient = {
        val config = new DefaultAsyncHttpClientConfig.Builder()
        val realm = new Realm.Builder(securityJson.username, securityJson.password)
          .setUsePreemptiveAuth(true)
          .setScheme(AuthScheme.BASIC)
          .build()
        new DefaultAsyncHttpClient(config.setRealm(realm).build)
      }

      solrServers = new CloudSolrServers(
        solrRunner.zkAddress,
        clusterStateUpdateInterval = 100 millis,
        defaultCollection = Some(collection1))

      // We need to configure a retry policy as otherwise requests fail because server status is not
      // updated fast enough...
      cut = AsyncSolrClient
        .Builder(RoundRobinLB(solrServers))
        .withRetryPolicy(RetryPolicy.TryAvailableServers)
        .withHttpClient(httpClient)
        .build

      awaitAllServersBeingEnabled()

      eventually {
        await(cut.query(collection1, q).map(getIds)) should contain theSameElementsAs someDocsIds
      }
    }

  }

  private def awaitAllServersBeingEnabled(): Assertion = {
    eventually(Timeout(30 seconds), Interval(100 milliseconds)) {
      logger.info("Awaiting all servers status is Enabled")
      cut.loadBalancer.solrServers.all.map(_.status) should contain theSameElementsAs solrServerUrls.map(_ => Enabled)
    }
  }

}
