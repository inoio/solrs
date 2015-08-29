package io.ino.solrs.future

import com.twitter.util.{Future => TFuture, Return, Throw}

import scala.util.{Failure, Success, Try => STry}

object TwitterFutureFactory extends FutureFactory[TFuture] {

  // Implicit val to provide s.th. to import.
  implicit val Implicit = TwitterFutureFactory

  def toBase[T]: (Future[T]) => TFuture[T] = {
    case sf: TwitterFuture[T] => sf.inner
    case _ => throw new Exception("Wrong future type")
  }

  class TwitterFuture[+T](f: TFuture[T]) extends FutureBase[T] {
    val inner = f

    override def onComplete[U](func: (STry[T]) => U): Unit = {
      inner.respond {
        case Return(value)  => func(Success(value))
        case Throw(t)       => func(Failure(t))
      }
    }

    def map[S](f: T => S): Future[S] = {
      val p = new TwitterPromise[S]
      inner.respond {
        case Return(value)  => mapSuccess(p, f, value)
        case Throw(t)       => p.failure(t)
      }
      p.future
    }

    def flatMap[S](f: T => Future[S]): Future[S] = {
      val p = new TwitterPromise[S]
      inner.respond {
        case Return(value)  => flatMapSuccess(p, f, value)
        case Throw(t)       => p.failure(t)
      }
      p.future
    }

    override def handle[U >: T](pf: PartialFunction[Throwable, U]): Future[U] = {
      val p = new TwitterPromise[U]
      inner.respond {
        case Return(value)  => p.success(value)
        case Throw(t)       => handleFailure(p, pf, t)
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
      val p = new TwitterPromise[U]

      inner.respond {
        case Return(value)  => p.success(value)
        case Throw(t)       => handleWithFailure(p, pf, t)
      }

      p.future
    }

  }

  class TwitterPromise[T] extends Promise[T] {
    val inner = com.twitter.util.Promise.apply[T]()
    def future: Future[T] = new TwitterFuture(inner.map(x => x))
    def success(value: T): Unit = inner.setValue(value)
    def failure(exception: Throwable): Unit = inner.setException(exception)
  }

  def newPromise[T] = new TwitterPromise[T]

}