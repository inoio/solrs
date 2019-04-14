package io.ino.solrs.future

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CompletionStage, ExecutionException, TimeUnit}

import com.twitter.util.{Future => TFuture}
import io.ino.solrs.FutureAwaits
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.{Future => SFuture}

class ScalaFutureFactorySpec extends FutureFactorySpec[SFuture] with FutureAwaits {
  import scala.concurrent.duration._
  override protected lazy val factory = ScalaFutureFactory
  override protected def awaitBase[T](future: SFuture[T]): T = await(future)(1.second)
}

class TwitterFutureFactorySpec extends FutureFactorySpec[TFuture] with FutureAwaits {
  import com.twitter.conversions.DurationOps._
  import com.twitter.util.Await
  override protected lazy val factory = TwitterFutureFactory
  override protected def awaitBase[T](future: TFuture[T]): T = Await.result(future, 1.second)
}

class JavaFutureFactorySpec extends FutureFactorySpec[CompletionStage] with FutureAwaits {
  override protected lazy val factory = new JavaFutureFactory
  override protected def awaitBase[T](future: CompletionStage[T]): T = {
    try {
      future.toCompletableFuture.get(1, TimeUnit.SECONDS)
    } catch {
      case e: ExecutionException => throw e.getCause
    }
  }
}

abstract class FutureFactorySpec[BaseFuture[_]] extends FunSpec with Matchers with MockitoSugar {

  protected implicit def factory: FutureFactory[BaseFuture]
  protected def awaitBase[T](future: BaseFuture[T]): T

  private lazy val classUnderTest = {
    val res = factory.getClass.getSimpleName
    if(res.endsWith("$")) res.dropRight(1) else res
  }

  describe(classUnderTest) {

    it("should return promise/future that supports map") {
      val (promise, future) = promiseAndFuture[String]
      val res = future.map(_.toUpperCase)
      promise.success("foo")
      res.get shouldBe "FOO"
    }

    it("should return promise/future that supports flatMap (success)") {
      val (promise, future) = promiseAndFuture[String]
      val res = future.flatMap { v =>
        val futurePromise = factory.newPromise[String]
        futurePromise.success(v.toUpperCase)
        futurePromise.future
      }
      promise.success("foo")
      res.get shouldBe "FOO"
    }

    it("should return promise/future that supports flatMap (failure in subject)") {
      val (promise, future) = promiseAndFuture[String]
      val res = future.flatMap { v =>
        val futurePromise = factory.newPromise[String]
        futurePromise.success("foo")
        futurePromise.future
      }
      val ex = new RuntimeException("fail!")
      promise.failure(ex)
      the[Throwable] thrownBy res.get shouldBe ex
    }

    it("should return promise/future that supports flatMap (failure in callback)") {
      val (promise, future) = promiseAndFuture[String]
      val futureException = new RuntimeException("fail!")
      val res = future.flatMap { v =>
        val futurePromise = factory.newPromise[String]
        futurePromise.failure(futureException)
        futurePromise.future
      }
      promise.success("foo")
      the[Throwable] thrownBy res.get shouldBe futureException
    }

    it("should return promise/future that supports handle (unhandled)") {
      testHandleUnhandled { (future, handledRef) =>
        future.handle { case e: UnhandledException =>
          handledRef.set(e)
          "should not be used!"
        }
      }
    }

    it("should return promise/future that supports handle (success)") {
      testHandleSuccess { (future, handledRef) =>
        val res = "handled"
        (future.handle { case e =>
          handledRef.set(e)
          res
        }, res)
      }
    }

    it("should return promise/future that supports handle (failure)") {
      testHandleFailure { (future, handledRef) =>
        val futureException = new RuntimeException("fail!")
        (future.handle { case e =>
          handledRef.set(e)
          throw futureException
        }, futureException)
      }
    }

    it("should return promise/future that supports handleWith (unhandled)") {
      testHandleUnhandled { (future, handledRef) =>
        future.handleWith { case e: UnhandledException =>
          handledRef.set(e)
          factory.failed(new RuntimeException("should not be thrown!"))
        }
      }
    }

    it("should return promise/future that supports handleWith (success)") {
      testHandleSuccess { (future, handledRef) =>
        val res = "handled"
        (future.handleWith { case e =>
          handledRef.set(e)
          val handlerPromise = factory.newPromise[String]
          handlerPromise.success(res)
          handlerPromise.future
        }, res)
      }
    }

    it("should return promise/future that supports handleWith (failure)") {
      testHandleFailure { (future, handledRef) =>
        val futureException = new RuntimeException("fail!")
        (future.handleWith { case e =>
          handledRef.set(e)
          factory.failed(futureException)
        }, futureException)
      }
    }

    it("should reduce a sequence of futures to a future of sequence") {
      val (promise1, future1) = promiseAndFuture[String]
      promise1.success("foo")

      FutureFactory.sequence(Seq(future1)).get shouldBe Seq("foo")

      val (promise2, future2) = promiseAndFuture[String]
      val ex = new scala.IllegalArgumentException("future2fail")
      promise2.failure(ex)

      the[Throwable] thrownBy FutureFactory.sequence(Seq(future1, future2)).get shouldBe ex
    }

  }

  private def promiseAndFuture[T]: (Promise[T], Future[T]) = {
    val promise = factory.newPromise[T]
    (promise, promise.future)
  }

  private def testHandleUnhandled(handle: (Future[String], AtomicReference[Throwable]) => Future[String]): Unit = {
    val (promise, future) = promiseAndFuture[String]

    val handled = new AtomicReference[Throwable]()
    val res = handle(future, handled)

    val ex = new scala.IllegalArgumentException("foo")
    promise.failure(ex)

    handled.get() should equal(null)
    the[Throwable] thrownBy res.get shouldBe ex
  }

  private def testHandleSuccess(handle: (Future[String], AtomicReference[Throwable]) => (Future[String], String)): Unit = {
    val (promise, future) = promiseAndFuture[String]

    val handled = new AtomicReference[Throwable]()
    val (res, expectedValue) = handle(future, handled)

    val ex = new scala.IllegalArgumentException("foo")
    promise.failure(ex)

    handled.get() shouldBe ex
    res.get shouldBe expectedValue
  }

  private def testHandleFailure(handle: (Future[String], AtomicReference[Throwable]) => (Future[String], Throwable)): Unit = {
    val (promise, future) = promiseAndFuture[String]

    val handled = new AtomicReference[Throwable]()
    val (res, expectedException) = handle(future, handled)

    val ex = new scala.IllegalArgumentException("foo")
    promise.failure(ex)

    handled.get() shouldBe ex

    the[Throwable] thrownBy res.get shouldBe expectedException
  }

  private implicit class RichFuture[T](f: Future[T]) {
    def get: T = awaitBase(factory.toBase(f))
  }

  case class UnhandledException(msg: String) extends RuntimeException(msg)

}
