package io.ino.solrs

import io.ino.solrs.future.ScalaFutureFactory
import org.scalatest._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import org.apache.solr.client.solrj.ResponseParser
import org.apache.solr.client.solrj.request.RequestWriter
import org.asynchttpclient.AsyncHttpClient

/**
 * Default FunSpec mixing in various standard traits, and also ScalaFutureFactory.
 */
//noinspection TypeAnnotation
abstract class StandardFunSpec extends AnyFunSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with Matchers
  with FutureAwaits
  with NewMockitoSugar {

  protected implicit val futureFactory: ScalaFutureFactory.type = ScalaFutureFactory

  protected val ascFactory: (LoadBalancer, AsyncHttpClient, Boolean, Option[RequestInterceptor], RequestWriter, ResponseParser, Metrics, Option[ServerStateObservation[Future]], RetryPolicy) => AsyncSolrClient[Future] = AsyncSolrClient.ascFactory[Future] _

}
