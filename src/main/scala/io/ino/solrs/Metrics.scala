package io.ino.solrs

trait Metrics {

  def requestTime(timeInMillis: Long): Unit

  def countRemoteException: Unit

  def countTransformResponseException: Unit

  /**
   * Counter for other exceptions, e.g. from AsyncHandler.onThrowable:
   * <em>
   * Invoked when an unexpected exception occurs during the processing of the response.
   * The exception may have been produced by implementation of onXXXReceived method invocation.
   * </em>
   */
  def countException: Unit
}

object NoopMetrics extends Metrics {
  override def requestTime(timeInMillis: Long) = {}
  override def countException = {}
  override def countRemoteException = {}
  override def countTransformResponseException = {}
}

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry._
import java.util.concurrent.TimeUnit._

class CodaHaleMetrics(val registry: MetricRegistry = new MetricRegistry()) extends Metrics {

  private val requestTimer = registry.timer(name(classOf[AsyncSolrClient], "requests"))

  private val remoteSolrExceptionCounter = registry.meter(name(classOf[AsyncSolrClient], "remote-exceptions"))
  private val transformResponseExceptionCounter = registry.meter(name(classOf[AsyncSolrClient], "transform-response-exceptions"))
  private val exceptionCounter = registry.meter(name(classOf[AsyncSolrClient], "other-exceptions"))

  override def requestTime(timeInMillis: Long) = requestTimer.update(timeInMillis, MILLISECONDS)

  override def countRemoteException = remoteSolrExceptionCounter.mark()

  override def countTransformResponseException = transformResponseExceptionCounter.mark()

  override def countException = exceptionCounter.mark()

}
