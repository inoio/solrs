package io.ino.solrs

import io.ino.solrs.RetryPolicy._
import io.ino.solrs.RetryDecision.Result
import org.apache.solr.client.solrj.SolrQuery
import org.scalatest.{Inside, FunSpec, Matchers}
import scala.concurrent.duration._
import scala.language.postfixOps

class RetryPolicySpec extends FunSpec with Matchers with Inside {

  private val e = new RuntimeException("simulated ex")
  private val server1 = SolrServer("host1")
  private val q = new SolrQuery("foo")

  describe("RetryPolicy.TryOnce") {
    it("should not retry") {
      val retry = TryOnce.shouldRetry(e, server1, QueryContext(q), new SingleServerLB(server1))
      retry.result should be (Result.Fail)
    }
  }

  describe("RetryPolicy.TryAvailableServers") {

    it("should not retry when no servers available") {
      val lb = new LoadBalancer {
        override def solrServer(q: SolrQuery, preferred: Option[SolrServer] = None): Option[SolrServer] = None
        override val solrServers = new StaticSolrServers(IndexedSeq.empty)
      }
      val retry = TryAvailableServers.shouldRetry(e, server1, QueryContext(q), lb)
      retry.result should be (Result.Fail)
    }

    it("should not retry when all servers already tried") {
      val server2 = SolrServer("host2")
      val lb = new RoundRobinLB(new StaticSolrServers(IndexedSeq(server1, server2)))
      val retry = TryAvailableServers.shouldRetry(e, server2, QueryContext(q).failedRequest(server1, 1 milli, e), lb)
      retry.result should be (Result.Fail)
    }

    it("should retry server not yet tried") {
      val server2 = SolrServer("host2")
      val lb = new RoundRobinLB(new StaticSolrServers(IndexedSeq(server1, server2)))
      val retry = TryAvailableServers.shouldRetry(e, server1, QueryContext(q), lb)
      retry should be (RetryServer(server2))
    }

  }

  describe("RetryPolicy.AtMost(x)") {

    it("should retry when limit is not reached") {
      val retry = AtMost(1).shouldRetry(e, server1, QueryContext(q), new SingleServerLB(server1))
      retry.result should be (Result.Retry)
    }

    it("should not retry when limit is reached") {
      val retry = AtMost(1).shouldRetry(e, server1, QueryContext(q).failedRequest(server1, 1 milli, e), new SingleServerLB(server1))
      retry.result should be (Result.Fail)
    }

  }
}
