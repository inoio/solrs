package io.ino.solrs

import java.util.concurrent.{Executors, TimeUnit}

import io.ino.concurrent.Execution
import io.ino.time.Clock
import io.ino.time.Units.Millisecond
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait LoadBalancer extends RequestInterceptor {

  val solrServers: SolrServers


  /**
   * Determines the solr server to use for a new request.
   */
  def solrServer(q: SolrQuery): Option[SolrServer]

  /**
   * Intercept the given query, allows implementations to monitor solr server performance.
   * This default implementation use invokes the query function.
   */
  override def interceptQuery(f: (SolrServer, SolrQuery) => Future[QueryResponse])
                             (solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {
    f(solrServer, q)
  }

  /**
   * On creation of AsyncSolrClient this method is invoked with the created instance.
   * Subclasses can override this method to get access to the solr client.
   */
  def setAsyncSolrClient(solr: AsyncSolrClient): Unit = {
    // empty default
  }

}

class SingleServerLB(val server: SolrServer) extends LoadBalancer {
  def this(baseUrl: String) = this(SolrServer(baseUrl))
  private val someServer = Some(server)
  override def solrServer(q: SolrQuery) = someServer
  override val solrServers: SolrServers = new StaticSolrServers(IndexedSeq(server))
}

class RoundRobinLB(override val solrServers: SolrServers) extends LoadBalancer {

  private var idx = 0

  /**
   * Start from the given idx and try servers.length servers to get the next available.
   */
  @tailrec
  private def findAvailable(servers: IndexedSeq[SolrServer], startIndex: Int, round: Int = 0): (Int, Option[SolrServer]) = {
    if (round == servers.length) {
      (startIndex, None)
    } else {
      val server = servers(startIndex)
      if (server.status == Enabled) {
        (startIndex, Some(server))
      } else {
        val nextIndex = (startIndex + 1) % servers.length
        findAvailable(servers, nextIndex, round + 1)
      }
    }
  }

  override def solrServer(q: SolrQuery): Option[SolrServer] = {
    val servers = solrServers.matching(q)
    if(servers.isEmpty) {
      None
    } else {
      // idx + 1 might be > servers.length, so let's use % to get a valid start position
      val startIndex = (idx + 1) % servers.length
      val (newIndex, result) = findAvailable(servers, startIndex)
      idx = newIndex
      result
    }
  }

}
object RoundRobinLB {
  def apply(solrServers: SolrServers): RoundRobinLB = new RoundRobinLB(solrServers)
  def apply(baseUrls: IndexedSeq[String]): RoundRobinLB = new RoundRobinLB(StaticSolrServers(baseUrls))
}

/**
 * LB strategy that selects the fastest server based on the latest average response time.
 * It's aimed to handle a multi datacenter setup where some server regularly need longer to response.
 * It shall also detect short (subsecond) pauses of a server e.g. due to garbage collection or s.th. else.
 *
 * The latest average response time is determined in the following order (the first found measure is used):
 * - currently still running requests (if they're lasting longer than previous, already completed requests)
 * - average response time of the current or the last second
 * - average response time of the last ten seconds
 * - total average resonse time
 *
 * The response time is measured using the given testQuery. A dedicated test query is used, because
 * queries can have very different performance characteristics, so that it might even be hard for an application
 * to classify this. With the testQuery you have control what is used to measure response time.
 *
 * For "normal" / "fast" servers (default: with a resonse time <= the average of all servers, you can override
 * `determineFastServers`) test queries are run whenever a request comes in, but between test queries at least
 * the minDelay has to be passed.
 * This way servers are not hammered with requests when nobody else is using your search, but if there's
 * high traffic this load balancer get high resolution of monitoring data to detect short pauses as well.
 *
 * For "slow" servers (default: response time > average) tests are run using the specified maxDelay.
 *
 * Directly after creation of this LoadBalancer / AsnycSolrClient multiple test queries are run (according
 * to the specified `initialTestRuns`, by default 10) to have initial stats.
 *
 * @param solrServers solr servers to load balance, those are regularly tested.
 * @param testQuery the query used to test solr servers
 * @param minDelay the minimum delay between the response of a test and the start of
 *                 the next test (to limit test frequency)
 * @param maxDelay the delay between tests for slow servers (or all servers if there are no real requests)
 * @param initialTestRuns on start each active server is tested the given number of
 *                        times to gather initial stats and determine fast/slow servers.
 * @param clock the clock to get the current time from. The tests using the maxDelay are
 *              run using a scheduled executor, therefore this interval uses the system clock
 */
class FastestServerLB(override val solrServers: SolrServers,
                testQuery: SolrQuery = new SolrQuery("*:*"),
                minDelay: Duration = 100 millis,
                maxDelay: Duration = 10 seconds,
                initialTestRuns: Int = 10,
                clock: Clock = Clock.systemDefault) extends LoadBalancer {

  private val logger = LoggerFactory.getLogger(getClass)

  private var client: AsyncSolrClient = _

  private val queryClass = "testQuery"

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  private[solrs] val statsByServer = TrieMap.empty[SolrServer, PerformanceStats]
  private[solrs] val serverTestTimestamp = TrieMap.empty[SolrServer, Millisecond].withDefaultValue(Millisecond(0))
  // "fast" servers are faster than the average of all servers, they're tested more frequently than slow servers.
  // slow servers e.g. are running in a separate dc and are not expected to suddenly perform significantly better
  private var fastServers = Set.empty[SolrServer]

  scheduleTests()
  scheduleUpdateStats()

  def shutdown(): Unit = {
    scheduler.shutdownNow()
  }

  protected def scheduleTests(): Unit = {
    scheduler.schedule(new Runnable {
      override def run(): Unit = {

        val maybeTestFutures = solrServers.all.filter(_.status == Enabled).map(testWithMinDelay)
        val testFutures = maybeTestFutures.collect { case Some(future) => future }

        import Execution.Implicits.sameThreadContext
        Future.sequence(testFutures).onComplete {
          _ => scheduleTests()
        }

      }
    }, maxDelay.toMillis, TimeUnit.MILLISECONDS)
  }

  protected def scheduleUpdateStats(): Unit = {
    scheduler.schedule(new Runnable {
      override def run(): Unit = updateStats()
    }, 1, TimeUnit.SECONDS)
  }

  override def setAsyncSolrClient(client: AsyncSolrClient): Unit = {
    this.client = client
    
    // gather initial stats
    import Execution.Implicits.sameThreadContext
    val futures = solrServers.all.filter(_.status == Enabled).map { server =>
      (1 to initialTestRuns).foldLeft(Future.successful(Unit))((res, i) =>
        res.flatMap(_ => test(server).map(_ => Unit))
      )
    }
    Future.sequence(futures).onComplete(_ =>
      updateStats()
    )
  }

  /**
   * Determines the solr server to use for a new request.
   */
  override def solrServer(q: SolrQuery): Option[SolrServer] = {
    val servers = solrServers.matching(q).filter(_.status == Enabled)
    if(servers.isEmpty) {
      None
    } else {
      val (serverIdx, result) = findBestServer(servers)
      Some(result)
    }
  }

  /**
   * Intercept user queries to trigger test queries based on the current request rate.
   */
  override def interceptQuery(f: (SolrServer, SolrQuery) => Future[QueryResponse])
                             (solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {
    val res = f(solrServer, q)
    // test each (fast) server matching the given query
    solrServers
      .matching(q)
      .filter(server => server.status == Enabled && fastServers.contains(server))
      .foreach(testWithMinDelay)
    res
  }

  /**
   * Start from the given idx and try servers.length servers to get the next available.
   */
  private def findBestServer(servers: IndexedSeq[SolrServer]): (Int, SolrServer) = {
    // servers.groupBy(server => serverStats(server).averageDuration(queryClass))
    val sorted = servers.sortBy(server => stats(server).predictDuration(queryClass))

    val fastest = sorted.head
    (servers.indexOf(fastest), fastest)
  }

  private def testWithMinDelay(server: SolrServer): Option[Future[QueryResponse]] = {
    // println(s"${clock.millis()} - ${serverTestTimestamp(server).value} - ${minDelay.toMillis} ")
    if(clock.millis() > serverTestTimestamp(server).value + minDelay.toMillis)
      Some(test(server))
    else
      None
  }

  private[solrs] def test(server: SolrServer): Future[QueryResponse] = {

    // store the timestamp before the test starts, so that a new test is not triggered when there's already one running
    serverTestTimestamp.update(server, Millisecond(clock.millis()))

    val request = stats(server).requestStarted(queryClass)
    val res = client.doQuery(server, testQuery)

    res.onComplete { _ =>
      request.finished()
      // we want to limit the delay between requests, i.e. the time between the end of a test and the
      // start of the new test. Otherwise, when test queries are running for a long time, we would send more
      // and more test queries to the server
      serverTestTimestamp.update(server, Millisecond(clock.millis()))
    }(Execution.Implicits.sameThreadContext)

    res
  }

  private[solrs] def updateStats(): Unit = {
    statsByServer.values.foreach(_.updateStats())
    val previousFastServers = fastServers
    fastServers = determineFastServers
    if(logger.isInfoEnabled && fastServers != previousFastServers) {
      val others = statsByServer.keys.filterNot(fastServers.contains)
      logger.info(s"Updated fast servers: $fastServers (previous: $previousFastServers, others: $others)")
    }
  }

  /**
   * Determines the servers that are tested more frequently.
   */
  protected def determineFastServers: Set[SolrServer] = {
    if(statsByServer.isEmpty) Set.empty
    else {
      val durationByServer = statsByServer.mapValues(_.predictDuration(queryClass))
      val average = durationByServer.values.sum / durationByServer.size
      durationByServer.filter { case (server, duration) =>
        duration <= average
      }.keys.toSet
    }
  }

  private def stats(server: SolrServer): PerformanceStats = {
    statsByServer.getOrElseUpdate(server, new PerformanceStats(server, clock))
  }

}