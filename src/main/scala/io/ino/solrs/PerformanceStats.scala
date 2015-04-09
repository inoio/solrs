package io.ino.solrs

import java.util.NavigableSet
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit._

import io.ino.time.Clock
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

/**
 * Statistics for a solr server.
 */
class PerformanceStats(solrServer: SolrServer, clock: Clock) {

  private val logger = LoggerFactory.getLogger(getClass)

  import PerformanceStats._

  // the min size, for that currentRequests are checked for requests lasting longer than
  // currentRequestsRemoveThreshold
  protected val currentRequestsSizeCheckLimit = 10000
  // requests lasting longer than this are removed from currentRequests
  protected val currentRequestsRemoveThreshold = 10000L

  private val emptyMap = Map.empty[QueryClass, CountAndDuration]
  private var lastCalculatedSecond = -1L

  // store all current requests with their startedAtMillis, independently from the queryClass, to predict
  // response time based on the longest lasting request.
  // the idea is that a stop-the-world gc is affecting all requests, independently from the actual query
  private val currentRequests = TrieMap.empty[QueryClass, NavigableSet[RequestHandle]]
  // shortcut method, withDefault does not update the map...
  private def currentRequestsFor(queryClass: QueryClass): NavigableSet[RequestHandle] = {
    currentRequests.getOrElseUpdate(queryClass, new ConcurrentSkipListSet[RequestHandle])
  }

  // stores a bucket per second
  private val buckets = TrieMap.empty[Long, Bucket]
  private def bucket(second: Long): Bucket = buckets.getOrElseUpdate(second, new Bucket(second))

  // EvictingArray is "appending" => latest entry is at last position (array(array.length))
  private val requestAveragesPerSecond = EvictingArray.fill[Map[QueryClass, CountAndDuration]](60)(Map.empty)
  // Array, from index 0 = latest 10 seconds to index 5 = oldest 10 seconds
  private val requestAveragesPer10Seconds = Array.fill[Map[QueryClass, Duration]](6)(Map.empty)

  // overall average
  private val totalCountAndDuration = TrieMap.empty[QueryClass, CountAndDuration].withDefaultValue(0 -> 0L)

  def requestStarted(queryClass: QueryClass): RequestHandle = {
    new RequestHandle {
      override val startedAtMillis = clock.millis()
      // store this request, so that it can be used for prediction
      currentRequestsFor(queryClass).add(this)
      override def finished(): Unit = {
        val finishedAtMillis = clock.millis()
        val finishedAtSecond = MILLISECONDS.toSeconds(finishedAtMillis)
        // println(s"Request finished, updating bucket for second $finishedAtSecond")
        bucket(finishedAtSecond).register(queryClass, finishedAtMillis - startedAtMillis)
        currentRequests(queryClass).remove(this)
      }
    }
  }

  /**
   * Update stats from recorded requests.
   */
  def updateStats(): Unit = {
    val nowSecond = MILLISECONDS.toSeconds(clock.millis())

    val seconds = if(lastCalculatedSecond == -1) {
      buckets.keys.toSeq.sorted.headOption match {
        case Some(second) => second to nowSecond - 1
        case None => IndexedSeq.empty
      }
    } else {
      lastCalculatedSecond + 1 to nowSecond - 1
    }

    updateSecondsFromBuckets(seconds)
    lastCalculatedSecond = nowSecond - 1

    update10SecondAverages()

    // clean up concurrent requests if it's growing unexpectedly... to prevent memory leaks if some requests are not finished
    // those requests will no longer be available for prediction
    currentRequests.values.foreach { requests =>
      if(requests.size() > currentRequestsSizeCheckLimit) {
        import scala.collection.JavaConversions._
        val requestsToRemove = requests.filter(_.startedAtMillis > currentRequestsRemoveThreshold)
        logger.warn(s"Current requests exceed limit $currentRequestsSizeCheckLimit, removing ${requestsToRemove.size} requests for cleanup.")
        requestsToRemove.foreach(entry => requests.remove(entry))
      }
    }

  }

  private def update10SecondAverages(): Unit = {
    val allAverages = requestAveragesPerSecond.values.reverse
    // logger.trace(s"Updating lastMinuteIn10Seconds from allAverages ${allAverages.mkString(", ")}")
    for (idx <- 0 until 6) {
      val from = idx * 10
      val until = from + 10
      val averages = allAverages.slice(from, until)
      val countsAndDurationsByQueryClass = averages.foldLeft(emptyMap.withDefault(queryClass => 0 -> 0L)) { case (res, averagesForSecond) =>
        if (averagesForSecond.isEmpty) res
        else {
          averagesForSecond.foldLeft(res) { case (res2, (queryClass, (countForSecond, averageForSecond))) =>
            val (count, durations) = res2(queryClass)
            val x = res2.updated(queryClass, (count + countForSecond) -> (durations + countForSecond * averageForSecond))
            x
          }
        }
      }
      val averagesByQueryClass = countsAndDurationsByQueryClass.mapValues { case (count, durationSum) => durationSum / count }
      requestAveragesPer10Seconds.update(idx, averagesByQueryClass)
    }
  }

  private def updateSecondsFromBuckets(seconds: IndexedSeq[Duration]): Unit = {
    for (second <- seconds) {
      buckets.get(second) match {
        case Some(bucket) =>
          val averageDurations = bucket.averageDurations
          requestAveragesPerSecond.add(averageDurations)
          updateTotalCountAndDuration(averageDurations)
          buckets.remove(second)
        case None =>
          // println(s"No bucket found for second $second")
          requestAveragesPerSecond.add(emptyMap)
      }
    }
  }

  def averageDurationForSecond(queryClass: QueryClass, relativeSecond: Int): Option[Long] = {
    requestAveragesPerSecond.lastUpdate(relativeSecond).get(queryClass).map {
      case (count, duration) => duration
    }
  }

  def dumpStats(queryClass: QueryClass): Unit = {
    val sb = new StringBuilder()
    sb.append(s"====== stats for [${solrServer.baseUrl}][queryClass $queryClass] at ${clock.millis()} millis ======")
    import scala.collection.JavaConversions._
    currentRequests(queryClass).foreach(req => sb.append(s"\n[currentRequest] $req"))
    buckets.foreach { case (sec, bucket) =>
      bucket.averageDuration(queryClass).foreach(duration => sb.append(s"\n[bucket(sec $sec)] $duration"))
    }
    requestAveragesPer10Seconds.zipWithIndex.foreach { case (queryClassAndDuratin, idx) =>
      queryClassAndDuratin.get(queryClass).foreach(duration => sb.append(s"\n[10seconds(${idx*10} - ${(idx+1)*10})] $duration"))
    }
    logger.info(sb.toString())
    logger.info(s"\n====== END OF stats for [${solrServer.baseUrl}][queryClass $queryClass] ======")
  }

  def averageDurationFor10Seconds(queryClass: QueryClass, relativeTenSeconds: Int): Option[Long] = {
    // val idx = 10 + relativeSecond
    requestAveragesPer10Seconds(math.abs(relativeTenSeconds)).get(queryClass)
  }

  def totalAverageDuration(queryClass: QueryClass): Long = {
    val (count, duration) = totalCountAndDuration(queryClass)
    if(count == 0) {
      0
    } else {
      duration / count
    }
  }

  def predictDuration(queryClass: QueryClass): Long = {
    val millis = clock.millis()

    val finishedRequestsAverage: Duration = {
      val currentSecond = MILLISECONDS.toSeconds(millis)
      buckets.get(currentSecond).flatMap( currentSecondBucket =>
        currentSecondBucket.averageDuration(queryClass)
      ).getOrElse(
          averageDurationForSecond(queryClass, 0)
            .getOrElse(
              averageDurationFor10Seconds(queryClass, 0)
                .getOrElse(
                  totalAverageDuration(queryClass))))
    }

    // use pollFirst because first() throws NoSuchElementException if empty
    Option(currentRequests(queryClass).pollFirst()).flatMap { oldestRunningRequest =>
      if(millis - oldestRunningRequest.startedAtMillis > finishedRequestsAverage) {
        Some(millis - oldestRunningRequest.startedAtMillis)
      } else {
        None
      }
    }.getOrElse {
      finishedRequestsAverage
    }

  }

  private def updateTotalCountAndDuration(measurements: Map[QueryClass, CountAndDuration]): Unit = {
    measurements.foreach { case (queryClass, (count, duration)) =>
      // println(s"Recording measurements ${measurements.mkString(", ")}")
      val (oldCount, oldDuration) = totalCountAndDuration(queryClass)
      totalCountAndDuration.update(queryClass, (oldCount + count) -> (oldDuration + count * duration))
    }
  }

}

object PerformanceStats {

  type QueryClass = String
  type Count = Int
  type Duration = Long
  type CountAndDuration = (Count, Duration)

  class CountsAndDurations {

    private val requestCounts = TrieMap.empty[QueryClass, Count].withDefaultValue(0)
    private val requestDurationsInMillis = TrieMap.empty[QueryClass, Duration].withDefaultValue(0)

    def averageDuration(queryClass: QueryClass): Option[Duration] = {
      val count = requestCounts(queryClass)
      if(count == 0) {
        None
      } else {
        Some(requestDurationsInMillis(queryClass) / count)
      }
    }
    
    def averageCountsAndDurations: Map[QueryClass, CountAndDuration] = {
      requestCounts.map { case ((queryClass, count)) =>
        (queryClass, count -> requestDurationsInMillis(queryClass) / count)
      }.toMap
    }

    def record(queryClass: QueryClass, durationInMillis: Duration): Unit = {
      requestCounts.update(queryClass, requestCounts(queryClass) + 1)
      requestDurationsInMillis.update(queryClass, requestDurationsInMillis(queryClass) + durationInMillis)
    }

    def recordAll(records: Map[QueryClass, Duration]): Unit = records.foreach { case (queryClass, duration) =>
      record(queryClass, duration)
    }

  }

  /**
   * Stores stats for a single second.
   */
  class Bucket(forSecond: Long) {

    private val serverStats = new CountsAndDurations

    val register = serverStats.record _
    
    def averageDurations = serverStats.averageCountsAndDurations

    def averageDuration = serverStats.averageDuration _

  }

  trait RequestHandle extends Comparable[RequestHandle] {
    def startedAtMillis: Long
    def finished(): Unit
    override def compareTo(o: RequestHandle): Int = (startedAtMillis - o.startedAtMillis).toInt
    override def toString: String = {
      s"RequestHandle[startedAt=$startedAtMillis]"
    }
  }

  /**
   * Mutable, fixed sized array of Long values, which evicts old values.
   * E.g. an EvictingArray of size 2 with added values 1, 2, 3 will have stored the values 2, 3.
   */
  class EvictingArray[T: ClassTag] private(_values: Array[T]) {

    private val size = _values.size
    private var length = 0
    private var pos = 0

    def isEmpty: Boolean = length == 0

    def values: Array[T] = {
      if(length < size) {
        _values.take(length)
      } else {
        val (a, b) = _values.splitAt(pos)
        (b ++ a).take(length)
      }
    }

    /**
     * Adds the given value to the array. If the array was already full so that the oldest element is dropped,
     * this will be returned.
     */
    def add(value: T): Option[T] = {
      val res = if(length == size) Some(_values(pos)) else None
      _values.update(pos, value)
      if(pos == size - 1) pos = 0 else pos += 1
      if(length < size) length += 1
      res
    }

    def lastUpdatePos: Int = {
      if(length == 0) {
        -1
      } else if(pos == 0) {
        size - 1
      } else {
        pos - 1
      }
    }

    def lastUpdate(relative: Int): T = {
      assert(relative <= 0, "the relative update must be <= 0")
      if(math.abs(relative) > length) {
        throw new IllegalArgumentException(s"Not enough updates ($length) for requested relative $relative")
      }
      val position = pos - 1 + relative
      _values(if(position < 0) size + position else position)
    }

    @throws[IndexOutOfBoundsException]
    def apply(index: Int): T = {
      if(index >= length) {
        throw new IndexOutOfBoundsException(s"Maximum index ${length - 1} exceeded: $index")
      }
      if(length < size) {
        _values(index)
      } else {
        val d = pos + index
        if(d >= size) {
          _values(d - size)
        } else {
          _values(d)
        }
      }
    }

  }

  object EvictingArray {

    def apply[T: ClassTag](size: Int): EvictingArray[T] = new EvictingArray[T](Array.ofDim[T](size))

    def fill[T: ClassTag](size: Int)(elem: => T): EvictingArray[T] = new EvictingArray[T](Array.fill[T](size)(elem))

  }

}