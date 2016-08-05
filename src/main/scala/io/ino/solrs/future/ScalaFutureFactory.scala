package io.ino.solrs.future

import io.ino.concurrent.Execution

import scala.concurrent.{Future => SFuture, Promise => SPromise}
import scala.util.Try

/** Factory for standard scala futures */
object ScalaFutureFactory extends FutureFactory[SFuture] {
  import scala.util.{Failure, Success}

  // Implicit val to provide s.th. to import.
  implicit val Implicit = ScalaFutureFactory

  def toBase[T]: (Future[T]) => SFuture[T] = {
    case sf: ScalaFuture[T] => sf.inner
    case _ => throw new Exception("Wrong future type")
  }

  class ScalaFuture[+T](f: SFuture[T]) extends FutureBase[T] {
    val inner = f

    import Execution.Implicits.sameThreadContext

    override def onComplete[U](func: (Try[T]) => U): Unit = inner.onComplete(func)

    def map[S](f: T => S): Future[S] = {
      val p = new ScalaPromise[S]
      inner.onComplete {
        case Success(value) => mapSuccess(p, f, value)
        case Failure(err)   => p.failure(err)
      }
      p.future
    }

    def flatMap[S](f: T => Future[S]): Future[S] = {
      val p = new ScalaPromise[S]
      inner.onComplete {
        case Success(value) => flatMapSuccess(p, f, value)
        case Failure(err)   => p.failure(err)
      }
      p.future
    }

    override def handle[U >: T](pf: PartialFunction[Throwable, U]): Future[U] = {
      val p = new ScalaPromise[U]
      inner.onComplete {
        case Success(value) => p.success(value)
        case Failure(t)     => handleFailure(p, pf, t)
      }
      p.future
    }

    /** Creates a new future that will handle any matching throwable that this
      * future might contain by assigning it a value of another future.
      *
      * If there is no match, or if this future contains
      * a valid result then the new future will contain the same result.
      *
      * Example:
      *
      * {{{
      *  val f = future { Int.MaxValue }
      *  future (6 / 0) handle { case e: ArithmeticException => f } // result: Int.MaxValue
      * }}}
      *
      * Like recoverWith from std lib, related to rescue/handle in twitter.
      */
    override def handleWith[U >: T](pf: PartialFunction[Throwable, Future[U]]): Future[U] = {
      val p = new ScalaPromise[U]
      inner.onComplete {
        case Success(value) => p.success(value)
        case Failure(t)     => handleWithFailure(p, pf, t)
      }
      p.future
    }

  }

  class ScalaPromise[T] extends Promise[T] {
    val inner = SPromise[T]()
    def future: Future[T] = new ScalaFuture(inner.future)
    def success(value: T): Unit = inner.success(value)
    def failure(exception: Throwable): Unit = inner.failure(exception)
  }

  def newPromise[T] = new ScalaPromise[T]

}
