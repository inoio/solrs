package io.ino.solrs.future

import io.ino.solrs.future._

object TwitterFactory extends Factory[com.twitter.util.Future] {

  def toBase[T]: (Future[T]) => com.twitter.util.Future[T] =
    (x: Future[T]) => {
      x match {
        case sf: TwitterFuture[T] =>
          sf.inner
        case _ => throw new Exception("Wrong future type")
      }
    }

  class TwitterFuture[+T](f: com.twitter.util.Future[T])
      extends Future[T] {
    val inner = f

    def map[S](f: T => S): Future[S] = {
      val p = new TwitterPromise[S]
      inner.onSuccess { value =>
        try
          p.success(f(value))
        catch {
          case err: Throwable =>
            p.failure(err)
        }
      }
      inner.onFailure { err => p.failure(err) }
      p.future
    }
    def flatMap[S](f: T => Future[S]): Future[S] = {
      val p = new TwitterPromise[S]
      inner.onSuccess { value =>
        try
          f(value).map(x => { p.success(x) })
        catch {
          case err: Throwable =>
            p.failure(err)
        }
      }
      inner.onFailure { err => p.failure(err) }
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

  object Helpers {

    implicit def fromTFToFuture[T](x: Future[T]): com.twitter.util.Future[T] =
      x match {
        case sf: TwitterFuture[T] =>
          sf.inner
        case _ => throw new Exception("Wrong future type")
      }
    implicit def fromFutureToSF[T](x: com.twitter.util.Future[T]): TwitterFuture[T] =
      new TwitterFuture(x)

  }
}