package io.ino.solrs

import io.ino.solrs.future.ScalaFutureFactory
import org.scalatest._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

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

  protected implicit val futureFactory = ScalaFutureFactory

  protected val ascFactory = AsyncSolrClient.ascFactory[Future] _

}
