package io.ino.solrs

import java.util.concurrent.TimeUnit._
import io.ino.solrs.PerformanceStats.EvictingArray
import io.ino.time.Clock
import io.ino.time.Clock.MutableClock
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.SortedMap
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.{Success, Try}

class PerformanceStatsSpec extends AnyFunSpec with Matchers with BeforeAndAfterEach {

  private val clock = new MutableClock
  private val queryClass1 = "q1"

  override def beforeEach(): Unit = {
    clock.resetToSystemMillis()
  }

  describe("PerformanceStats") {

    it("should calculate averages per second (for the last 10 seconds)") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // set clock to the start of a second, so that both measurements are in the same bucket
      val second1 =  MILLISECONDS.toSeconds(clock.millis())
      clock.set(second1 * 1000)

      request(cut, 10)
      request(cut, 30)

      // stats are calculated for each finished second / the previous second compared to "now",
      // therefore we must start the next second...
      val second2 = second1 + 1
      clock.set(second2 * 1000)

      cut.updateStats()
      cut.averageDurationForSecond(queryClass1, relativeSecond = 0) shouldBe Some(20)

      // no requests at second2

      val second3 =  second2 + 1
      clock.set(second3 * 1000)

      request(cut, 30)
      request(cut, 50)

      val second4 =  second3 + 1
      clock.set(second4 * 1000)

      cut.updateStats()

      cut.averageDurationForSecond(queryClass1, relativeSecond = 0) shouldBe Some(40)
      cut.averageDurationForSecond(queryClass1, relativeSecond = -1) shouldBe None
      cut.averageDurationForSecond(queryClass1, relativeSecond = -2) shouldBe Some(20)

    }

    it("should calculate averages for 10 second intervals") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate several requests, not only 1 per second, to see how averages per second are handled
      // 6 measurements, sum = 240 => average = 40
      requests(cut, SortedMap(0L -> 10L, 100L -> 20L, 1000L -> 30L, 6000L -> 40L, 7000L -> 60L, 7500L -> 80L))

      clock.advance(1000)
      cut.updateStats()

      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = 0) shouldBe Some(40)

      // 5 seconds later, the first 3 measurements should be part of the 2nd 10 second bucket
      clock.advance(5000)
      cut.updateStats()

      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = 0) shouldBe Some(60)
      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = -1) shouldBe Some(20)

      // 5 seconds more later, all measurements should be part of the 2nd 10 second bucket
      clock.advance(5000)
      cut.updateStats()

      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = 0) shouldBe None
      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = -1) shouldBe Some(40)
    }

    it("should store the overall average") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate several requests, not only 1 per second, to see how averages per second are handled
      // 6 measurements, sum = 240 => average = 40
      requests(cut, SortedMap(0L -> 10L, 100L -> 20L, 1000L -> 30L, 6000L -> 40L, 7000L -> 60L, 7500L -> 80L))

      clock.advance(1000)
      cut.updateStats()

      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = 0) shouldBe Some(40)
      cut.totalAverageDuration(queryClass1, 0) shouldBe 40

      // some more requests later
      requests(cut, SortedMap(20000L -> 50L, 21000L -> 60L, 22000L -> 60L, 23000L -> 60L, 24000L -> 60L, 25000L -> 70L))

      clock.advance(5000)
      cut.updateStats()

      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = 0) shouldBe Some(60)
      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = -1) shouldBe None
      cut.averageDurationFor10Seconds(queryClass1, relativeTenSeconds = -2) shouldBe Some(40)
      cut.totalAverageDuration(queryClass1, 0) shouldBe 50

      // after more than 60 seconds in total, the overall average should be 50
      clock.advance(60000)
      cut.updateStats()

      cut.totalAverageDuration(queryClass1, 0) shouldBe 50
    }

    /**
     * Currently running requests are the most useful information, at least we should assume
     * that a new request takes at least the time a currently running request took so far.
     * This is relevant in a scenario where e.g. a major GC is running.
     *
     * Of course only the times of requests should be taken into account that are so far running
     * longer compared to the average.
     */
    it("should predict the response time based on ongoing requests") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate some requests to generate some averages in the past
      requests(cut, SortedMap(0L -> 100L, 1000L -> 100L, 2000L -> 100L))
      cut.updateStats()

      // set clock to the end of the second to see that requests spanning seconds are measured
      // correctly
      clock.set(2999)

      // first request
      val request1 = cut.requestStarted(queryClass1)
      clock.advance(100)
      // second request, to see that multiple requests in parallel are supported
      val request2 = cut.requestStarted(queryClass1)
      clock.advance(100)

      // now request1 is running for 200 millis
      cut.predictDuration(queryClass1) shouldBe 200
    }

    it("should predict the response time based on finished requests from the current second") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate some requests in the past to generate some averages in the past
      requests(cut, SortedMap(0L -> 10L, 1000L -> 10L, 2000L -> 10L))
      cut.updateStats()

      requests(cut, SortedMap(3000L -> 100L, 3100L -> 100L, 3200L -> 100L, 3300L -> 100L, 3400L -> 100L))
      cut.predictDuration(queryClass1) shouldBe 100
    }

    /**
     * Currently running requests should only be taken into account if they are running
     * longer than the average.
     */
    it("should predict the response time based on finished requests from the current second, ignoring shorter active requests") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate some requests in the past to generate some averages in the past
      requests(cut, SortedMap(0L -> 10L, 1000L -> 10L, 2000L -> 10L))
      cut.updateStats()

      requests(cut, SortedMap(3000L -> 100L, 3100L -> 100L, 3200L -> 100L, 3300L -> 100L, 3400L -> 100L))

      // first active request
      val request1 = cut.requestStarted(queryClass1)
      clock.advance(10)
      // second request, to see that multiple requests in parallel are supported
      val request2 = cut.requestStarted(queryClass1)
      clock.advance(20)

      cut.predictDuration(queryClass1) shouldBe 100
    }

    it("should predict the response time based on the last 10 seconds") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate some requests in the past to generate some averages in the past
      requests(cut, SortedMap(0L -> 10L, 1000L -> 20L, 2000L -> 30L, 3000L -> 20L))

      clock.set(7000)

      cut.updateStats()

      cut.predictDuration(queryClass1) shouldBe 20
    }

    it("should predict the response time based on the overall average as fallback") {
      val cut = new PerformanceStats(SolrServer("host1"), 0, clock)

      // simulate some requests in the past to generate some averages in the past
      requests(cut, SortedMap(0L -> 10L, 100L -> 20L, 200L -> 30L, 300L -> 20L))
      // more to the next second so that the recorded stats are stored
      clock.advance(1000)
      cut.updateStats()

      // more far to the future
      clock.set(42424242424242L)

      cut.predictDuration(queryClass1) shouldBe 20
    }

  }

  describe("EvictingQueue") {

    it("should initially be empty") {
      val cut = EvictingArray[Long](3)
      cut.isEmpty shouldBe true
    }

    it("should return stored values") {
      val cut = EvictingArray[Long](3)
      cut.add(1)
      cut.add(2)
      cut.values shouldBe Array(1, 2)
    }

    it("should return a value by index") {
      val cut = EvictingArray[Long](2)

      an[IndexOutOfBoundsException] should be thrownBy cut(0)

      cut.add(1)
      cut(0) shouldBe 1

      cut.add(2)
      cut(0) shouldBe 1
      cut(1) shouldBe 2

      cut.add(3)
      cut(0) shouldBe 2
      cut(1) shouldBe 3
    }

    it("should evict old values") {
      val cut = EvictingArray[Long](3)
      cut.add(1)
      cut.add(2)
      cut.add(3)
      cut.add(4)
      cut.values shouldBe Array(2, 3, 4)
    }

    it("should return the oldest value if it's replaced") {
      val cut = EvictingArray[Long](2)
      cut.add(1) shouldBe None
      cut.add(2) shouldBe None
      cut.add(3) shouldBe Some(1)
    }

    it("should return the relative last updated value") {
      val cut = EvictingArray[Long](2)
      cut.add(1)
      cut.lastUpdate(0) shouldBe 1

      cut.add(2)
      cut.lastUpdate(-1) shouldBe 1
      cut.lastUpdate(0) shouldBe 2

      cut.add(3)
      cut.lastUpdate(-1) shouldBe 2
      cut.lastUpdate(0) shouldBe 3
    }

  }

  private def requests(stats: PerformanceStats, offsetAndDuration: SortedMap[Long, Long]): Unit = {
    offsetAndDuration.foreach { case (offset, duration) =>
        clock.set(offset)
        request(stats, duration)
    }
  }

  private def request(stats: PerformanceStats, duration: Long): Unit = {
    val request = stats.requestStarted(queryClass1)
    clock.advance(duration)
    request.finished()
  }

  private def atSecond[T](second: Long)(f: => T): T = {
    clock.advance(SECONDS.toMillis(second))
    f
  }

}
