package io.ino.solrs

import io.ino.solrs.LoadBalancer.NoSolrServersAvailableException
import io.ino.solrs.RetryPolicy._
import io.ino.solrs.RetryDecision.Result
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.request.QueryRequest
import org.scalatest.{FunSpec, Inside, Matchers}

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Try

class RetryPolicySpec extends FunSpec with Matchers with Inside {

  private val e = new RuntimeException("simulated ex")
  private val server1 = SolrServer("host1")
  private val q = new QueryRequest(new SolrQuery("foo"))

  describe("RetryPolicy.TryOnce") {
    it("should not retry") {
      val retry = TryOnce.shouldRetry(e, server1, RequestContext(q), new SingleServerLB(server1))
      retry.result should be (Result.Fail)
    }
  }

  describe("RetryPolicy.TryAvailableServers") {

    it("should not retry when no servers available") {
      val lb = new LoadBalancer {
        override def solrServer(r: SolrRequest[_], preferred: Option[SolrServer] = None): Try[SolrServer] = Failure(NoSolrServersAvailableException(Nil))
        override val solrServers = new StaticSolrServers(IndexedSeq.empty)
      }
      val retry = TryAvailableServers.shouldRetry(e, server1, RequestContext(q), lb)
      retry.result should be (Result.Fail)
    }

    it("should not retry when all servers already tried") {
      val server2 = SolrServer("host2")
      val lb = new RoundRobinLB(new StaticSolrServers(IndexedSeq(server1, server2)))
      val retry = TryAvailableServers.shouldRetry(e, server2, RequestContext(q).failedRequest(server1, 1 milli, e), lb)
      retry.result should be (Result.Fail)
    }

    it("should retry server not yet tried") {
      val server2 = SolrServer("host2")
      val lb = new RoundRobinLB(new StaticSolrServers(IndexedSeq(server1, server2)))
      val retry = TryAvailableServers.shouldRetry(e, server1, RequestContext(q), lb)
      retry should be (RetryServer(server2))
    }

  }

  describe("RetryPolicy.AtMost(x)") {

    it("should retry when limit is not reached") {
      val retry = AtMost(1).shouldRetry(e, server1, RequestContext(q), new SingleServerLB(server1))
      retry.result should be (Result.Retry)
    }

    it("should not retry when limit is reached") {
      val retry = AtMost(1).shouldRetry(e, server1, RequestContext(q).failedRequest(server1, 1 milli, e), new SingleServerLB(server1))
      retry.result should be (Result.Fail)
    }

  }
}
