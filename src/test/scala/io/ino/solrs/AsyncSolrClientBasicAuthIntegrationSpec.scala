package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.Http2SolrClient
import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Realm
import org.asynchttpclient.Realm.AuthScheme
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.IntegrationPatience

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Arrays.asList
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class AsyncSolrClientBasicAuthIntegrationSpec extends StandardFunSpec with Eventually with IntegrationPatience {

  private var solrs: AsyncSolrClient[Future] = _

  private var solrRunner: SolrRunner = _
  private var solrJClient: Http2SolrClient = _

  private val collection1 = "collection1"

  import io.ino.solrs.SolrUtils._

  override def beforeAll(): Unit = {
    solrRunner = SolrRunner.start(
      port = 8889,
      // read from src/test/resources, because sbt might not copy the symlink solr/collection1/conf when copying to target...
      maybeSolrHome = Some(Paths.get("./src/test/resources/solr-basic-auth").toAbsolutePath.normalize())
    )
    solrJClient = new Http2SolrClient.Builder("http://localhost:" + solrRunner.port + "/solr/collection1")
      .withBasicAuthCredentials("solr", "SolrRocks") // from https://solr.apache.org/guide/8_10/basic-authentication-plugin.html
      .build()
  }

  override def beforeEach(): Unit = {
    eventually {
      solrJClient.deleteByQuery("*:*")
    }
    val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
    val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
    solrJClient.add(asList(doc1, doc2))
    solrJClient.commit()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    solrs.shutdown()
    solrJClient.close()
    solrRunner.stop()
  }

  describe("Solr") {

    it("should support basic auth with a configured http client") {

      val httpClient = {
        val config = new DefaultAsyncHttpClientConfig.Builder()
        val realm = new Realm.Builder(securityJson.username, securityJson.password)
          .setUsePreemptiveAuth(true)
          .setScheme(AuthScheme.BASIC)
          .build()
        new DefaultAsyncHttpClient(config.setRealm(realm).build)
      }

      solrs = AsyncSolrClient.Builder(s"http://localhost:${solrRunner.port}/solr/collection1")
        .withHttpClient(httpClient)
        .build

      val response = solrs.query(new SolrQuery("cat:cat1"))

      await(response)(1.second).getResults.getNumFound should be (2)
    }

  }

}
