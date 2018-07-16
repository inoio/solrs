package io.ino.solrs

import java.util.concurrent.TimeUnit

import io.ino.solrs.SolrMatchers.hasQuery
import io.ino.time.Clock
import io.ino.time.Clock.MutableClock
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.request.UpdateRequest
import org.mockito.Matchers.{eq => mockEq, _}
import org.mockito.Mockito._
import org.scalactic.source.Position
import org.scalatest.concurrent.Eventually._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

//noinspection RedundantDefaultArgument
class FastestServerLBSpec extends StandardFunSpec {

  type FastestServerLB = io.ino.solrs.FastestServerLB[Future]
  type AsyncSolrClient = io.ino.solrs.AsyncSolrClient[Future]

  // use class under test as field so that we can safely shutdown it after each test
  private var cut: FastestServerLB = _

  private val server1 = SolrServer("host1")
  private val server2 = SolrServer("host2")
  private val server3 = SolrServer("host3")
  private val solrServers = new StaticSolrServers(IndexedSeq(server1, server2, server3))

  private val q = new SolrQuery("foo")
  private val r = new QueryRequest(q)
  private val classifyQuery: SolrQuery => String = solrQuery => "foo"

  private implicit val clock: MutableClock = Clock.mutable

  private val solrs = mock[AsyncSolrClient]

  import AsyncSolrClientMocks._

  override def beforeEach(): Unit = {
    reset(solrs)
    mockDoRequest(solrs)
    clock.set(0)
  }

  override def afterEach(): Unit = {
    cut.shutdown()
  }

  describe("FastestServerLB") {

    it("should return a Failure if no solr server matches") {
      val nonMatchingServers = new SolrServers {
        override def all: Seq[SolrServer] = Nil
        override def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]] = Success(Vector.empty)
      }
      val cut = newDynamicLB(nonMatchingServers)
      cut.solrServer(r) shouldBe a[Failure[_]]
    }

    it("should only return active solr servers") {
      val servers = IndexedSeq(SolrServer("host1"), SolrServer("host2"))
      val cut = newDynamicLB(new StaticSolrServers(servers))

      cut.solrServer(r) should be (Success(SolrServer("host1")))
      // we must create some performance stats for host1, so that host2 will be selected
      runTests(cut, server1, fromSecond = 1, toSecond = 2, startResponseTime = 1000, endResponseTime = 1000)
      cut.solrServer(r) should be (Success(SolrServer("host2")))

      servers.head.status = Disabled
      cut.solrServer(r) should be (Success(SolrServer("host2")))

      servers.head.status = Enabled
      servers(1).status = Failed
      cut.solrServer(r) should be (Success(SolrServer("host1")))
      cut.solrServer(r) should be (Success(SolrServer("host1")))

      servers.head.status = Disabled
      cut.solrServer(r) shouldBe a[Failure[_]]
    }

    it("should return the active leader for update requests") {
      val server1 = SolrServer("host1", isLeader = true)
      val server2 = SolrServer("host2", isLeader = false)
      val servers = IndexedSeq(server1, server2)
      val cut = newDynamicLB(new StaticSolrServers(servers))

      val r = new UpdateRequest()

      cut.solrServer(r) should be (Success(server1))
      // we must create some performance stats for host1, so that host2 would usually be selected (for non-update requests)
      runTests(cut, server1, fromSecond = 1, toSecond = 2, startResponseTime = 1000, endResponseTime = 1000)
      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server1))

      servers.head.status = Disabled
      cut.solrServer(r) should be (Success(server2))
      cut.solrServer(r) should be (Success(server2))

      servers.head.status = Enabled
      servers(1).status = Failed
      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server1))

      servers.head.status = Disabled
      cut.solrServer(r) shouldBe a[Failure[_]]
    }

    it("should return the fastest server by default") {
      val cut = newDynamicLB(solrServers)

      when(solrs.doExecute[QueryResponse](any(), any())(any())).thenReturn(delayedResponse(1))
      cut.test(server1)
      when(solrs.doExecute[QueryResponse](any(), any())(any())).thenReturn(delayedResponse(10))
      cut.test(server2)
      when(solrs.doExecute[QueryResponse](any(), any())(any())).thenReturn(delayedResponse(20))
      cut.test(server3)

      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server1))
    }

    /**
     * if servers are equally fast then the first one should not get all requests...
     */
    it("should round robin equally fast servers") {
      val cut = newDynamicLB(solrServers)

      runTests(cut, server1, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      runTests(cut, server2, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      runTests(cut, server3, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      cut.updateStats()

      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server2))
      cut.solrServer(r) should be (Success(server3))
      cut.solrServer(r) should be (Success(server1))
    }

    it("should consider the preferred server if it's one of the fastest servers") {
      val cut = newDynamicLB(solrServers)
      val preferred = Success(server2)

      runTests(cut, server1, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      runTests(cut, server2, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      runTests(cut, server3, fromSecond = 1, toSecond = 5, startResponseTime = 10, endResponseTime = 10)
      cut.updateStats()

      cut.solrServer(r, preferred = Some(server2)) should be (Success(server2))
      cut.solrServer(r, preferred = Some(server2)) should be (Success(server2))

      // if the preferred server is too slow then the fastest ones should be round robin'ed
      cut.solrServer(r, preferred = Some(server3)) should be (Success(server1))
      cut.solrServer(r, preferred = Some(server3)) should be (Success(server2))
    }

    it("should return the server with a better predicted response time") {
      val cut = newDynamicLB(solrServers)

      runTests(cut, server1, fromSecond = 1, toSecond = 5, startResponseTime = 10, endResponseTime = 10)
      runTests(cut, server2, fromSecond = 1, toSecond = 5, startResponseTime = 20, endResponseTime = 20)
      runTests(cut, server3, fromSecond = 1, toSecond = 5, startResponseTime = 20, endResponseTime = 20)
      cut.updateStats()

      runTests(cut, server1, fromSecond = 6, toSecond = 10, startResponseTime = 10, endResponseTime = 20)
      runTests(cut, server2, fromSecond = 6, toSecond = 10, startResponseTime = 20, endResponseTime = 10)
      cut.updateStats()

      cut.solrServer(r) should be (Success(server2))
    }

    /**
     * Response times 1 and 10 are obviously different, but 2 and 3 should be considered to be equal
     * and for them the round robin distribution should lead to better load balancing
     */
    it("should allow to quantize / consider (very) similar predicted response times to be equal") {
      // quantize to 5: 0 to 4 = 0, 5 to 9 = 1 etc.
      val cut = newDynamicLB(solrServers, mapPredictedResponseTime = t => t/5)

      runTests(cut, server1, fromSecond = 1, toSecond = 5, startResponseTime = 1, endResponseTime = 1)
      runTests(cut, server2, fromSecond = 1, toSecond = 5, startResponseTime = 2, endResponseTime = 2)
      runTests(cut, server3, fromSecond = 1, toSecond = 5, startResponseTime = 3, endResponseTime = 3)
      cut.updateStats()

      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server2))
      cut.solrServer(r) should be (Success(server3))
      cut.solrServer(r) should be (Success(server1))

      runTests(cut, server2, fromSecond = 6, toSecond = 10, startResponseTime = 10, endResponseTime = 10)
      runTests(cut, server3, fromSecond = 6, toSecond = 10, startResponseTime = 10, endResponseTime = 10)
      cut.updateStats()

      cut.solrServer(r) should be (Success(server1))
      cut.solrServer(r) should be (Success(server1))
    }

    it("should initially test servers to gather performance stats") {
      val cut = newDynamicLB(solrServers, minDelay = 10 millis)
      solrServers.all.foreach(s => verify(solrs).doExecute[QueryResponse](mockEq(s), hasQuery(q))(any()))
    }

    it("should test servers based on the real query rate restricted by min delay") {
      clock.set(0)
      val minDelay = 50 millis
      val (testQuery, cut, spyClient) = spiedClient(minDelay)

      solrServers.all.foreach(s => verify(spyClient, atLeastOnce()).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))

      // reset the mock to see which test queries are run after the initial ones
      reset(spyClient)
      mockDoRequest(spyClient)

      // simulate a 10 second delay until the next request
      clock.set((10 seconds).toMillis)
      solrServers.all.foreach(s => verify(spyClient, never()).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))

      // now simulate the query
      var realQuery = new SolrQuery("foo")
      spyClient.query(realQuery)
      verify(spyClient).doExecute[QueryResponse](any(), hasQuery(realQuery))(any())

      // verify that the lb ran the test query against all servers
      solrServers.all.foreach(s => verify(spyClient, times(1)).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))

      // another real query must not directly trigger new test queries
      realQuery = new SolrQuery("bar")
      spyClient.query(realQuery)
      verify(spyClient).doExecute[QueryResponse](any(), hasQuery(realQuery))(any())
      solrServers.all.foreach(s => verify(spyClient, times(1)).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))

      // if a query comes in at least minDelay later, servers should be tested again
      clock.advance(minDelay.toMillis)
      realQuery = new SolrQuery("baz")
      spyClient.query(realQuery)
      verify(spyClient).doExecute[QueryResponse](any(), hasQuery(realQuery))(any())
      solrServers.all.foreach(s => verify(spyClient, times(2)).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))
    }

    it("should test slow servers less frequently") {
      clock.set(0)
      val minDelay = 50 millis
      def mockRequests(spyClient: AsyncSolrClient): Unit = {
        // mock server1/server2 with ~10 millis response time, and server3 significantly higher
        mockDoRequest(spyClient, mockEq(server1), 8 millis)
        mockDoRequest(spyClient, mockEq(server2), 12 millis)
        mockDoRequest(spyClient, mockEq(server3), 30 millis)
      }
      val (testQuery, cut, spyClient) = spiedClient(minDelay, mockRequests = mockRequests)

      // reset the mock to see which test queries are run after the initial ones
      reset(spyClient)
      mockRequests(spyClient)

      // simulate a 10 second delay until the next request
      clock.set((10 seconds).toMillis)

      // now simulate the query
      var realQuery = new SolrQuery("foo")
      spyClient.query(realQuery)
      verify(spyClient).doExecute[QueryResponse](any(), hasQuery(realQuery))(any())

      // verify that the lb ran the test query against fast server servers, but not against the slow server
      List(server1, server2).foreach(s => verify(spyClient, times(1)).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))
      verify(spyClient, never()).doExecute[QueryResponse](mockEq(server3), hasQuery(testQuery))(any())
    }

    it("should test slow/all servers according to the specified maxDelay") {
      clock.set(0)
      val minDelay = 10 millis
      val maxDelay = 50 millis
      def mockRequests(spyClient: AsyncSolrClient): Unit = {
        // mock server1/server2 with ~5 millis response time, and server3 significantly higher
        mockDoRequest(spyClient, mockEq(server1), 4 millis)
        mockDoRequest(spyClient, mockEq(server2), 6 millis)
        mockDoRequest(spyClient, mockEq(server3), 20 millis)
      }
      val (testQuery, cut, spyClient) = spiedClient(minDelay, maxDelay, mockRequests)

      // reset the mock to see which test queries are run after the initial ones
      reset(spyClient)
      mockRequests(spyClient)

      // we must also update the clock to that the internal, time based tests let the tests run
      clock.advance(maxDelay.toMillis)

      // we have to wait because the max delay is realized via scheduled executor
      Thread.sleep(maxDelay.toMillis)

      // verify that the lb ran the test query (in this case for all servers)
      // ... and we accept a slight delay because the scheduler might be a bit inaccurate...
      eventually {
        solrServers.all.foreach(s => verify(spyClient, atLeastOnce()).doExecute[QueryResponse](mockEq(s), hasQuery(testQuery))(any()))
      }(PatienceConfig(timeout = maxDelay * 2, interval = maxDelay/10), Position.here)
    }
  }

  private def spiedClient(minDelay: Duration,
                          maxDelay: Duration = 10 seconds,
                          mockRequests: AsyncSolrClient => Unit = (spyClient: AsyncSolrClient) => mockDoRequest(spyClient)
                         ):(SolrQuery, FastestServerLB, AsyncSolrClient) = {
    val testQuery = new SolrQuery("testQuery")
    cut = new FastestServerLB(solrServers, _ => ("collection1", testQuery), minDelay, maxDelay, clock = clock)
    // we use a spy to have a real async solr client for that we can verify interactions
    var spyClient: AsyncSolrClient = null
    val realClient: AsyncSolrClient = new AsyncSolrClient.Builder(cut, ascFactory) {
      override protected def setOnAsyncSolrClientAwares(solr: AsyncSolrClient): Unit = {
        spyClient = spy(solr)
        mockRequests(spyClient)
        super.setOnAsyncSolrClientAwares(spyClient)
      }
    }.build
    (testQuery, cut, spyClient)
  }

  private def newDynamicLB(solrServers: SolrServers,
                           minDelay: Duration = 50 millis,
                           mapPredictedResponseTime: Long => Long = identity): FastestServerLB = {
    cut = new FastestServerLB(solrServers, _ => ("collection1", q), minDelay, maxDelay = 30 seconds, initialTestRuns = 1,
      mapPredictedResponseTime = mapPredictedResponseTime, clock = clock) {
      override protected def scheduleTests(): Unit = Unit
      override protected def scheduleUpdateStats(): Unit = Unit
    }
    cut.setAsyncSolrClient(solrs)
    cut
  }

  private def atSecond[T](second: Long)(f: => T): T = {
    clock.set(TimeUnit.SECONDS.toMillis(second))
    f
  }

  private def runTests(cut: FastestServerLB, server: SolrServer,
                                  fromSecond: Long, toSecond: Long,
                                  startResponseTime: Long, endResponseTime: Long): Unit = {
    val deltaPerStep = (endResponseTime - startResponseTime) / (toSecond - fromSecond)
    for(second <- fromSecond to toSecond) {
      atSecond(second) {
        val responseTime = startResponseTime + (second - fromSecond) * deltaPerStep
        when(solrs.doExecute[QueryResponse](any(), any())(any())).thenReturn(delayedResponse(responseTime))
        cut.test(server)
      }
    }
  }

}
