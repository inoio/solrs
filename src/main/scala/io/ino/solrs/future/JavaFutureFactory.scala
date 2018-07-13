package io.ino.solrs.future

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer

import scala.util.Try

/** Factory for java 8 CompletionStage */
class JavaFutureFactory extends FutureFactory[CompletionStage] {
  import scala.util.{Failure, Success}

  def toBase[T]: Future[T] => CompletionStage[T] = {
    case sf: JavaFuture[T] => sf.inner
    case _ => throw new Exception("Wrong future type")
  }

  class JavaFuture[T](private[JavaFutureFactory] val inner: CompletionStage[T]) extends FutureBase[T] {

    override def onComplete[U](func: Try[T] => U): Unit = {
      inner.whenComplete(new BiConsumer[T, Throwable] {
        override def accept(value: T, ex: Throwable): Unit = {
          val trie = if(value != null) Success(value) else Failure(ex)
          func(trie)
        }
      })
    }

    def map[S](f: T => S): Future[S] = {
      val p = new JavaPromise[S]
      inner.whenComplete(new BiConsumer[T, Throwable] {
        override def accept(value: T, ex: Throwable): Unit = {
          if(value != null) mapSuccess(p, f, value)
          else p.failure(ex)
        }
      })
      p.future
    }

    def flatMap[S](f: T => Future[S]): Future[S] = {
      val p = new JavaPromise[S]
      inner.whenComplete(new BiConsumer[T, Throwable] {
        override def accept(value: T, ex: Throwable): Unit = {
          if(value != null) flatMapSuccess(p,f, value)
          else p.failure(ex)
        }
      })
      p.future
    }

    override def handle[U >: T](pf: PartialFunction[Throwable, U]): Future[U] = {
      val p = new JavaPromise[U]
      inner.whenComplete(new BiConsumer[T, Throwable] {
        override def accept(value: T, ex: Throwable): Unit = {
          if(value != null) p.success(value)
          else handleFailure(p, pf, ex)
        }
      })
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
      val p = new JavaPromise[U]
      inner.whenComplete(new BiConsumer[T, Throwable] {
        override def accept(value: T, ex: Throwable): Unit = {
          if(value != null) p.success(value)
          else handleWithFailure(p, pf, ex)
        }
      })
      p.future
    }

  }

  private[JavaFutureFactory] class JavaPromise[T] extends Promise[T] {
    private val inner = new CompletableFuture[T]
    override lazy val future: Future[T] = new JavaFuture(inner)
    def success(value: T): Unit = inner.complete(value)
    def failure(exception: Throwable): Unit = inner.completeExceptionally(exception)
  }

  def newPromise[T] = new JavaPromise[T]

}

object JavaFutureFactory extends JavaFutureFactory