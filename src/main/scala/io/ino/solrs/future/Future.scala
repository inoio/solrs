package io.ino.solrs.future

import scala.collection.generic.CanBuildFrom
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

trait Future[+T] {

  def onComplete[U](func: Try[T] => U): Unit

  def map[S](f: T => S): Future[S]
  def flatMap[S](f: T => Future[S]): Future[S]

  def handle[U >: T](pf: PartialFunction[Throwable, U]): Future[U]

  /**  Creates a new future that will handle any matching throwable that this
    *  future might contain by assigning it a value of another future.
    *
    *  If there is no match, or if this future contains
    *  a valid result then the new future will contain the same result.
    *
    *  Example:
    *
    *  {{{
    *  val f = future { Int.MaxValue }
    *  future (6 / 0) handle { case e: ArithmeticException => f } // result: Int.MaxValue
    *  }}}
    *
    *  Like recoverWith from std lib, related to rescue/handle in twitter.
    */
  def handleWith[U >: T](pf: PartialFunction[Throwable, Future[U]]): Future[U]
}


abstract class FutureBase[+T] extends Future[T] {

  protected def mapSuccess[A, B](promise: Promise[B], f: A => B, value: A): Unit = {
    try
      promise.success(f(value))
    catch {
      case err: Throwable =>
        promise.failure(err)
    }
  }

  protected def flatMapSuccess[A, B](p: Promise[B], f: A => Future[B], value: A): Unit = {
    try
      f(value).onComplete {
        case Success(x) => p.success(x)
        case Failure(t) => p.failure(t)
      }
    catch {
      case err: Throwable =>
        p.failure(err)
    }
  }

  protected def handleFailure[U >: T](p: Promise[U], pf: PartialFunction[Throwable, U], t: Throwable): Unit = {
    try {
      if (pf.isDefinedAt(t)) p.success(pf(t))
      else p.failure(t)
    } catch {
      case NonFatal(pft) => p failure pft
    }
  }

  protected def handleWithFailure[U >: T](p: Promise[U], pf: PartialFunction[Throwable, Future[U]], t: Throwable): Any = {
    try {
      val x = pf.applyOrElse(t, (_: Throwable) => this)
      x.map {
        v => p.success(v)
      }.handle {
        case NonFatal(e) => p.failure(e)
      }
    } catch {
      case NonFatal(e) => p failure e
    }
  }

}

trait Promise[T <: Any] {
  def future: Future[T]
  def success(value: T): Unit
  def failure(exception: Throwable): Unit
}

trait FutureFactory[RFT[_]] {

  def successful[T](result: T): Future[T] = {
    val promise = newPromise[T]
    promise.success(result)
    promise.future
  }

  def failed[T](exception: Throwable): Future[T] = {
    val promise = newPromise[T]
    promise.failure(exception)
    promise.future
  }

  def newPromise[T]: Promise[T]

  def toBase[T]: (Future[T] => RFT[T])

}

private[solrs] object FutureFactory {

  /** Simple version of `Futures.traverse`. Transforms a `TraversableOnce[Future[A]]` into a `Future[TraversableOnce[A]]`.
    *  Useful for reducing many `Future`s into a single `Future`.
    *
    *  Implemented not directly in class FutureFactory because this would be impossible to be implemented
    *  by a java FutureFactory.
    */
  def sequence[A, M[_] <: TraversableOnce[_], X[_]](in: M[Future[A]])
                                             (implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]],
                                              futureFactory: FutureFactory[X]): Future[M[A]] = {
    in.foldLeft(futureFactory.successful(cbf(in))) { (fr, fa) =>
      for (r <- fr; a <- fa.asInstanceOf[Future[A]]) yield r += a
    } map (_.result())
  }

}