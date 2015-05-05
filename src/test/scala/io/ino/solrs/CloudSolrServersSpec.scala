package io.ino.solrs

import io.ino.solrs.CloudSolrServers.WarmupQueries
import io.ino.time.Clock
import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Test that starts ZK, solrRunners and our Class Under Test before all tests.
 */
class CloudSolrServersSpec extends FunSpec with Matchers {

  import ServerStateChangeObservable._

  describe("CloudSolrServers") {

    it("should compute state changes correctly") {

      val events = CloudSolrServers.diff(
        oldState = Map(
          "col1" -> Seq(SolrServer("h10", Enabled)),
          "col2" -> Seq(SolrServer("h20", Enabled), SolrServer("h21", Enabled), SolrServer("h22", Disabled))
        ),
        newState = Map(
          "col2" -> Seq(SolrServer("h21", Enabled), SolrServer("h22", Enabled), SolrServer("h23", Enabled)),
          "col3" -> Seq(SolrServer("h30", Enabled))
        )
      )

      implicit val solrServerOrd = Ordering[String].on[SolrServer](s => s.baseUrl)

      events should contain theSameElementsAs Seq(
        Removed(SolrServer("h10", Enabled), "col1"),
        Removed(SolrServer("h20", Enabled), "col2"),
        StateChanged(SolrServer("h22", Disabled), SolrServer("h22", Enabled), "col2"),
        Added(SolrServer("h23", Enabled), "col2"),
        Added(SolrServer("h30", Enabled), "col3")
      )

    }

  }

}
