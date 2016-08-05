package io.ino.solrs

import io.ino.solrs.future.Future
import io.ino.solrs.future.ScalaFutureFactory.ScalaFuture

import scala.concurrent.{Await, Future => SFuture}
import scala.concurrent.duration.Duration

trait FutureAwaits {

  def await[T](future: SFuture[T])(implicit timeout: Duration): T = Await.result(future, timeout)
  def awaitReady[T](future: SFuture[T])(implicit timeout: Duration): SFuture[T] = Await.ready(future, timeout)

  def await[T](future: Future[T])(implicit timeout: Duration): T = future match {
    case f: ScalaFuture[T] => await(f.inner)
    case x => throw new IllegalArgumentException(s"Future of type ${x.getClass} not supported.")
  }
  def awaitReady[T](future: Future[T])(implicit timeout: Duration): SFuture[T] = future match {
    case f: ScalaFuture[T] => awaitReady(f.inner)
    case x => throw new IllegalArgumentException(s"Future of type ${x.getClass} not supported.")
  }

}
