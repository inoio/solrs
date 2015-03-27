package io.ino.solrs.future

trait Future[+T] {
  def map[S](f: T => S): Future[S]
  def flatMap[S](f: T => Future[S]): Future[S]
}

trait Promise[T <: Any] {
  def future: Future[T]
  def success(value: T): Unit
  def failure(exception: Throwable): Unit
}

trait Factory[RFT[_]] {
  def newPromise[T]: Promise[T]

  def toBase[T]: (Future[T] => RFT[T])
}

/* plain Scala default implementation */
object ScalaFactory extends Factory[scala.concurrent.Future] {
  import scala.util.{ Success, Failure }

  def toBase[T] = (f: Future[T]) => {
    f match {
      case sf: ScalaFuture[T] =>
        sf.inner
      case _ => throw new Exception("Wrong future type")
    }
  }

  class ScalaFuture[+T](f: scala.concurrent.Future[T])
      extends Future[T] {
    val inner = f
    //please complete this before usage if needed
    lazy val executionContextPromise = scala.concurrent.Promise[scala.concurrent.ExecutionContext]
    lazy val defaultExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    private lazy implicit val executionContext =
      try
        scala.concurrent.Await.result(
          executionContextPromise.future, scala.concurrent.duration.Duration.Zero)
      catch {
        case _: Throwable => defaultExecutionContext
      }

    def map[S](f: T => S): Future[S] = {
      implicit val ec = implicitly[scala.concurrent.ExecutionContext]
      val p = new ScalaPromise[S]
      inner.onComplete {
        case Success(value) =>
          try
            p.success(f(value))
          catch {
            case err: Throwable =>
              p.failure(err)
          }
        case Failure(err) =>
          p.failure(err)
      }(ec)
      p.future
    }
    def flatMap[S](f: T => Future[S]): Future[S] = {
      implicit val ec = implicitly[scala.concurrent.ExecutionContext]
      val p = new ScalaPromise[S]
      inner.onComplete {
        case Success(value) =>
          try
            f(value).map(x => p.success(x))
          catch {
            case err: Throwable =>
              p.failure(err)
          }
        case Failure(err) => p.failure(err)
      }(ec)
      p.future
    }
  }

  class ScalaPromise[T] extends Promise[T] {
    val inner = scala.concurrent.Promise.apply[T]()
    def future: Future[T] = new ScalaFuture(inner.future)
    def success(value: T): Unit = inner.success(value)
    def failure(exception: Throwable): Unit = inner.failure(exception)
  }

  def newPromise[T] = new ScalaPromise[T]

}