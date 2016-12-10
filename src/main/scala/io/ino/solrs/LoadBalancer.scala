package io.ino.solrs

import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import io.ino.solrs.ServerStateChangeObservable.Removed
import io.ino.solrs.ServerStateChangeObservable.StateChange
import io.ino.solrs.ServerStateChangeObservable.StateChanged
import io.ino.solrs.future.JavaFutureFactory
import io.ino.solrs.future.Future
import io.ino.solrs.future.FutureFactory
import io.ino.time.Clock
import io.ino.time.Units.Millisecond
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.language.postfixOps

trait LoadBalancer extends RequestInterceptor {

  val solrServers: SolrServers

  /**
   * Determines the solr server to use for a new request.
   */
  def solrServer(q: SolrQuery, preferred: Option[SolrServer]): Option[SolrServer]

  /**
   * Intercept the given query, allows implementations to monitor solr server performance.
   * This default implementation use invokes the query function.
   */
  override def interceptQuery(f: (SolrServer, SolrQuery) => Future[QueryResponse])
                             (solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {
    f(solrServer, q)
  }

  def shutdown(): Unit = {
    // empty default
  }

}

class SingleServerLB(val server: SolrServer) extends LoadBalancer {
  def this(baseUrl: String) = this(SolrServer(baseUrl))
  private val someServer = Some(server)
  override def solrServer(q: SolrQuery, preferred: Option[SolrServer] = None) = someServer
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

  override def solrServer(q: SolrQuery, preferred: Option[SolrServer] = None): Option[SolrServer] = {
    val servers = solrServers.matching(q)
    if(servers.isEmpty) {
      None
    } else {
      if(preferred.isDefined && servers.exists(s => s.baseUrl == preferred.get.baseUrl && s.status == Enabled)) {
        preferred
      } else {
        // idx + 1 might be > servers.length, so let's use % to get a valid start position
        val startIndex = (idx + 1) % servers.length
        val (newIndex, result) = findAvailable(servers, startIndex)
        idx = newIndex
        result
      }
    }
  }

}
object RoundRobinLB {
  def apply(solrServers: SolrServers): RoundRobinLB = new RoundRobinLB(solrServers)
  def apply(baseUrls: IndexedSeq[String]): RoundRobinLB = new RoundRobinLB(StaticSolrServers(baseUrls))

  /* Java API */
  import scala.collection.JavaConverters._
  def create(baseUrls: java.lang.Iterable[String]): RoundRobinLB = apply(baseUrls.asScala.toIndexedSeq)
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
 * @param collectionAndTestQuery a function that returns the collection name and a testQuery for the given server.
 *                               The collection is used to partition server when classifying "fast"/"slow" servers,
 *                               because for different collections response times will be different.
 *                               It's somehow similar with the testQuery: it might be different per server, e.g.
 *                               some server might only provide a /suggest handler while others provide /select
 *                               (which can be specified via the "qt" query param in the test query).
 * @param minDelay the minimum delay between the response of a test and the start of
 *                 the next test (to limit test frequency)
 * @param maxDelay the delay between tests for slow servers (or all servers if there are no real requests)
 * @param initialTestRuns on start each active server is tested the given number of
 *                        times to gather initial stats and determine fast/slow servers.
 * @param filterFastServers a function to filter fast / preferred servers. The function takes the calculated average duration
 *                          of all servers of a collection, and returns a function for a SolrServer->Duration tuple
 *                          that returns true/false to indicate if a server should be considered "fast".
 *                          The default value for filterFastServers uses `duration <= average * 1.1 + 5` (use
 *                          1.1 as multiplier to accepted some deviation, for smaller values like 1 or 2 millis
 *                          also add some fix value to allow normal deviation).
 * @param mapPredictedResponseTime a function that's applied to the predicted response time. This can e.g. be used to
 *                                 quantize the time so that minor differences are ignored.
 * @param clock the clock to get the current time from. The tests using the maxDelay are
 *              run using a scheduled executor, therefore this interval uses the system clock
 */
class FastestServerLB[F[_]](override val solrServers: SolrServers,
                            val collectionAndTestQuery: SolrServer => (String, SolrQuery),
                            minDelay: Duration = 100 millis,
                            maxDelay: Duration = 10 seconds,
                            initialTestRuns: Int = 10,
                            filterFastServers: Long => ((SolrServer, Long)) => Boolean =
                            average => { case (_, duration) => duration <= average * 1.1 + 5 },
                            val mapPredictedResponseTime: Long => Long = identity,
                            clock: Clock = Clock.systemDefault)
                           (implicit futureFactory: FutureFactory[F]) extends LoadBalancer with AsyncSolrClientAware[F] with FastestServerLBJmxSupport[F] {

  import FastestServerLB._

  private val logger = LoggerFactory.getLogger(getClass)

  private var client: AsyncSolrClient[F] = _

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  protected[solrs] val statsByServer = TrieMap.empty[SolrServer, PerformanceStats]
  protected[solrs] val serverTestTimestamp = TrieMap.empty[SolrServer, Millisecond].withDefaultValue(Millisecond(0))
  // "fast" servers are faster than the average of all servers, they're tested more frequently than slow servers.
  // slow servers e.g. are running in a separate dc and are not expected to suddenly perform significantly better
  protected var fastServersByCollection = Map.empty[String, Set[SolrServer]].withDefaultValue(Set.empty)
  private val lastServerIdx = new AtomicInteger(-1)

  private def collection(server: SolrServer): String = collectionAndTestQuery(server)._1
  private def testQuery(server: SolrServer): SolrQuery = collectionAndTestQuery(server)._2

  init()

  protected def init(): Unit = {
    subscribeToServerChanges()
    scheduleTests()
    scheduleUpdateStats()
    initJmx()
  }

  override def shutdown(): Unit = {
    scheduler.shutdownNow()
    shutdownJmx()
  }

  // servers that become inactive should be removed from stats, because these stats are no longer valid
  // when a server comes active again (e.g. it might just not yet be completely warmed up)
  protected def subscribeToServerChanges(): Unit = {
    solrServers match {
      case observable: ServerStateChangeObservable => observable.register(new StateChangeObserver {
        override def onStateChange(event: StateChange): Unit = event match {
          case Removed(server, collection) if server.isEnabled =>
            // clean up
            statsByServer.remove(server)
            serverTestTimestamp.remove(server)
          case StateChanged(from, to, collection) if from.isEnabled && !to.isEnabled =>
            statsByServer.remove(from)
            serverTestTimestamp.remove(from)
          case _ => // ignore
        }
      })
      case _ => // nothing to do
    }
  }

  protected def scheduleTests(): Unit = {
    scheduler.schedule(new Runnable {
      override def run(): Unit = {

        val maybeTestFutures = solrServers.all.filter(_.status == Enabled).map(testWithMinDelay)
        val testFutures = maybeTestFutures.collect { case Some(future) => future }

        FutureFactory.sequence(testFutures).onComplete {
          _ => scheduleTests()
        }

      }
    }, maxDelay.toMillis, TimeUnit.MILLISECONDS)
  }

  protected def scheduleUpdateStats(): Unit = {
    // delay the first run until the next second starts
    val initialDelay = clock.millis() % 1000
    scheduler.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = try {
        updateStats()
      } catch {
        // catch all exceptions, because an uncaught exception would prevent further
        // executions of this task.
        case e: Throwable =>
          logger.error("An error occurred when trying to updateStats().", e)
      }
    }, initialDelay, 1000, TimeUnit.MILLISECONDS)
  }

  override def setAsyncSolrClient(client: AsyncSolrClient[F]): Unit = {
    this.client = client

    // gather initial stats
    val futures = solrServers.all.filter(_.status == Enabled).map { server =>
      (1 to initialTestRuns).foldLeft(futureFactory.successful(Unit))((res, i) =>
        res.flatMap(_ => test(server).map(_ => Unit))
      )
    }
    FutureFactory.sequence(futures).onComplete(_ =>
      updateStats()
    )
  }

  /**
   * Determines the solr server to use for a new request.
   */
  override def solrServer(q: SolrQuery, preferred: Option[SolrServer] = None): Option[SolrServer] = {
    val servers = solrServers.matching(q).filter(_.status == Enabled)
    if(servers.isEmpty) {
      None
    } else {
      val (serverIdx, result) = findBestServer(servers, lastServerIdx.get(), preferred)
      lastServerIdx.lazySet(serverIdx)
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
      .filter { server =>
        val fastServers = fastServersByCollection(collection(server))
        server.status == Enabled &&  fastServers.contains(server)
      }
      .foreach(testWithMinDelay)
    res
  }

  /**
   * Start from the given idx and try servers.length servers to get the next available.
   */
  private def findBestServer(servers: IndexedSeq[SolrServer], lastServerIdx: Int, preferred: Option[SolrServer]): (Int, SolrServer) = {
    val serversByDuration = servers.groupBy(server =>
      mapPredictedResponseTime(stats(server).predictDuration(TestQueryClass))
    ).toSeq.sortBy(_._1)
    val fastestServers = serversByDuration.head._2


    if(preferred.isDefined && fastestServers.exists(s => s.baseUrl == preferred.get.baseUrl && s.status == Enabled)) {
      // if the preferred server is chosen, the last server index is returned as is, round robin shall not be affected
      // e.g. if the previous query got server 1, then another client got server 2, then client 1 asks to get server 1
      // again -> the next client should get server 3
      (lastServerIdx, preferred.get)
    } else {
      val idx = if (lastServerIdx + 1 < fastestServers.size) lastServerIdx + 1 else 0
      (idx, fastestServers(idx))
    }
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

    val request = stats(server).requestStarted(TestQueryClass)
    val res = client.doQuery(server, testQuery(server))

    res.onComplete { _ =>
      request.finished()
      // we want to limit the delay between requests, i.e. the time between the end of a test and the
      // start of the new test. Otherwise, when test queries are running for a long time, we would send more
      // and more test queries to the server
      serverTestTimestamp.update(server, Millisecond(clock.millis()))
    }

    res
  }

  private[solrs] def updateStats(): Unit = {
    statsByServer.values.foreach(_.updateStats())
    updateFastServers()
  }

  /**
   * Determines the servers that are tested more frequently.
   */
  protected def updateFastServers(): Unit = {
    if(statsByServer.isEmpty) Map.empty
    else {
      val serversByCollection = statsByServer.keys.toSet.groupBy(collection)
      serversByCollection.foreach { case (collection, servers) =>
        val durationByServer = statsByServer.filterKeys(servers.contains).mapValues(_.predictDuration(TestQueryClass))
        val average = durationByServer.values.sum / durationByServer.size
        val serverFilter = filterFastServers(average)
        val fastServers = durationByServer.filter(serverFilter).keys.toSet
        if(fastServers != fastServersByCollection(collection)) {
          onBeforeFastServersChanged(collection, fastServers, durationByServer, average)
          fastServersByCollection += (collection -> fastServers)
        }
      }
    }
  }

  protected def onBeforeFastServersChanged(collection: String,
                                          fastServers: Set[SolrServer],
                                          durationByServer: scala.collection.Map[SolrServer, Long],
                                          average: Long): Unit = {
    if (logger.isInfoEnabled) {
      logger.info(s"Updating fast servers ($collection): $fastServers (average: $average, durationByServer: ${durationByServer.mkString(", ")})")
    }
  }

  protected def stats(server: SolrServer): PerformanceStats = {
    statsByServer.getOrElseUpdate(server, new PerformanceStats(server, initialPredictedResponseTime, clock))
  }

  protected def initialPredictedResponseTime: Long = {
    // just some fairly high value, should be bigger than usual predicted response times
    1000
  }

}

object FastestServerLB {

  val TestQueryClass = "testQuery"

  /* Java API */
  import java.lang.{Boolean => JBoolean}
  import java.lang.{Long => JLong}
  import java.util.function.BiFunction
  import java.util.function.LongFunction
  import java.util.function.{Function => JFunction}
  case class Builder(solrServers: SolrServers,
                     collectionAndTestQuery: SolrServer => (String, SolrQuery),
                     minDelay: Duration = 100 millis,
                     maxDelay: Duration = 10 seconds,
                     initialTestRuns: Int = 10,
                     filterFastServers: Long => ((SolrServer, Long)) => Boolean =
                     average => {
                       case (_, duration) => duration <= average * 1.1 + 5
                     },
                     mapPredictedResponseTime: Long => Long = identity,
                     clock: Clock = Clock.systemDefault) {

    /** The minimum delay between the response of a test and the start of the next test (to limit test frequency) */
    def withMinDelay(value: Long, unit: TimeUnit): Builder = copy(minDelay = FiniteDuration(value, unit))

    /** The delay between tests for slow servers (or all servers if there are no real requests) */
    def withMaxDelay(value: Long, unit: TimeUnit): Builder = copy(maxDelay = FiniteDuration(value, unit))

    /** On start each active server is tested the given number of times to gather initial stats and determine fast/slow servers. */
    def withInitialTestRuns(count: Int): Builder = copy(initialTestRuns = count)

    /** A function to filter fast / preferred servers. The function takes the calculated average duration of all servers
      * of a collection, and returns a function for a SolrServer->Duration tuple that returns true/false to indicate if
      * a server should be considered "fast".<br/>
      * The default value for filterFastServers uses `duration <= average * 1.1 + 5` (use 1.1 as multiplier to accept
      * some deviation, for smaller values like 1 or 2 millis also add some fix value to allow normal deviation). */
    def withFilterFastServers(filter: LongFunction[BiFunction[SolrServer, JLong, JBoolean]]): Builder = copy(
      filterFastServers = average => {
        case (server, duration) => filter(average)(server, duration)
      }
    )

    /** A function that's applied to the predicted response time. This can e.g. be used to quantize the time so that minor differences are ignored. */
    def withMapPredictedResponseTime(mapPredictedResponseTime: JFunction[JLong, JLong]): Builder = copy(mapPredictedResponseTime = input => mapPredictedResponseTime(input))

    /** The clock to get the current time from. */
    def withClock(clock: Clock): Builder = copy(clock = clock)

    def build(): FastestServerLB[CompletionStage] = build(JavaFutureFactory)

    def build[F[_]](implicit futureFactory: FutureFactory[F]) = new FastestServerLB[F](
      solrServers,
      collectionAndTestQuery,
      minDelay,
      maxDelay,
      initialTestRuns,
      filterFastServers,
      mapPredictedResponseTime,
      clock
    )

  }

  /**
    *
    * @param solrServers solr servers to load balance, those are regularly tested.
    * @param collectionAndTestQuery a function that returns the collection name and a testQuery for the given server.
    *                               The collection is used to partition server when classifying "fast"/"slow" servers,
    *                               because for different collections response times will be different.
    *                               It's somehow similar with the testQuery: it might be different per server, e.g.
    *                               some server might only provide a /suggest handler while others provide /select
    *                               (which can be specified via the "qt" query param in the test query).
    */
  def builder(solrServers: SolrServers, collectionAndTestQuery: JFunction[SolrServer, (String, SolrQuery)]): Builder =
    Builder(solrServers, server => collectionAndTestQuery(server))

}

import javax.management.openmbean.CompositeData
import javax.management.openmbean.TabularData

/**
 * JMX MBean for FastestServerLB.
 */
trait FastestServerLBMBean /*extends DynamicMBean*/ {

  def fastServers(collection: String): CompositeData

  def predictDurations(collection: String): CompositeData

  def quantizePredictedDurations(collection: String): CompositeData

  def averagesPerSecond(collection: String): TabularData

  def averagesPer10Seconds(collection: String): TabularData

  def averagesTotalAverage(collection: String): CompositeData

}

import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.openmbean._

import scala.collection.JavaConverters._

/**
 * JMX support for FastestServerLB, implementation of FastestServerLBMBean, to be mixed into FastestServerLB.
 */
trait FastestServerLBJmxSupport[F[_]] extends FastestServerLBMBean { self: FastestServerLB[F] =>

  import FastestServerLB._
  import FastestServerLBJmxSupport._

  def initJmx(): Unit = {
    ManagementFactory.getPlatformMBeanServer.registerMBean(this, ObjName)
  }

  def shutdownJmx(): Unit = {
    ManagementFactory.getPlatformMBeanServer.unregisterMBean(ObjName)
  }

  override def averagesPerSecond(collection: String): TabularData = {
    val servers = serversByCollection(collection)
    val itemNames = Array("Second") ++ servers.map(_.baseUrl)
    val (ct, table) = compositeTypeAndTable(itemNames)
    for (second <- -20 to 0) {
      table.put(new CompositeDataSupport(ct, (Map("Second" -> "-%02d".format(-second)) ++
        servers.map(s => s.baseUrl -> stats(s).averageDurationForSecond(TestQueryClass, second).map(_.toString).getOrElse("-")).toMap[String, String]).asJava))
    }
    table
  }

  override def averagesPer10Seconds(collection: String): TabularData = {
    val servers = serversByCollection(collection)
    val itemNames = Array("10 Seconds") ++ servers.map(_.baseUrl)
    val (ct, table) = compositeTypeAndTable(itemNames)
    for (tenSeconds <- 0 to 5) {
      table.put(new CompositeDataSupport(ct, (Map("10 Seconds" -> s"-$tenSeconds") ++
        servers.map(s => s.baseUrl -> stats(s).averageDurationFor10Seconds(TestQueryClass, tenSeconds).map(_.toString).getOrElse("-")).toMap[String, String]).asJava))
    }
    table
  }

  override def averagesTotalAverage(collection: String): CompositeData = {
    val (servers, serverNames) = serversAndNames(collection)
    new CompositeDataSupport(
      compositeType(serverNames),
      servers.map(s => s.baseUrl -> stats(s).totalAverageDuration(TestQueryClass, -1).toString).toMap[String, String].asJava)
  }

  override def predictDurations(collection: String): CompositeData = {
    val (servers, serverNames) = serversAndNames(collection)
    new CompositeDataSupport(
      compositeType(serverNames),
      servers.map(s => s.baseUrl -> stats(s).predictDuration(TestQueryClass).toString).toMap[String, String].asJava)
  }

  override def quantizePredictedDurations(collection: String): CompositeData = {
    val (servers, serverNames) = serversAndNames(collection)
    new CompositeDataSupport(
      compositeType(serverNames),
      servers.map(s => s.baseUrl -> mapPredictedResponseTime(stats(s).predictDuration(TestQueryClass)).toString).toMap[String, String].asJava)
  }

  override def fastServers(collection: String): CompositeData = {
    val servers = fastServersByCollection(collection).toArray
    new CompositeDataSupport(
      compositeType(servers.map(_.baseUrl)),
      servers.map(s => s.baseUrl -> stats(s).totalAverageDuration(TestQueryClass, -1).toString).toMap[String, String].asJava)
  }

  private def serversAndNames(collection: String): (Array[SolrServer], Array[String]) = {
    val servers = serversByCollection(collection)
    (servers, servers.map(_.baseUrl))
  }

  private def serversByCollection(collection: String): Array[SolrServer] = {
    statsByServer.keys.filter(collectionAndTestQuery(_)._1 == collection).toArray
  }

  private def compositeType(itemNames: Array[String]): CompositeType = {
    new CompositeType("Stats", "Server stats", itemNames, itemNames, itemNames.map(_ => SimpleType.STRING))
  }

  private def compositeTypeAndTable(itemNames: Array[String]): (CompositeType, TabularData) = {
    val ct = compositeType(itemNames)
    val table = new TabularDataSupport(new TabularType("ServerStats", "Table server stats", ct, itemNames))
    (ct, table)
  }

}

object FastestServerLBJmxSupport {

  val ObjName = new ObjectName("io.ino.solrs:type=FastestServerLB")

}