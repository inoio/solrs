package io.ino.solrs

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

/**
 * Created by magro on 4/27/14.
 */
trait FutureAwaits {

  def await[T](future: Future[T])(implicit timeout: Duration): T = Await.result(future, timeout)
  def awaitReady[T](future: Future[T])(implicit timeout: Duration): Future[T] = Await.ready(future, timeout)

}
