package io.ino.solrs

import io.ino.solrs.future.ScalaFutureFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSpec, Matchers}

import scala.concurrent.Future

/**
 * Default FunSpec mixing in various standard traits, and also ScalaFutureFactory.
 */
//noinspection TypeAnnotation
abstract class StandardFunSpec extends FunSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with Matchers
  with FutureAwaits
  with NewMockitoSugar {

  protected implicit val futureFactory = ScalaFutureFactory

  protected val ascFactory = AsyncSolrClient.ascFactory[Future] _

}
