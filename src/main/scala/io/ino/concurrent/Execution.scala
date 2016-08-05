package io.ino.concurrent

import scala.concurrent.ExecutionContext

object Execution {

  val sameThreadContext = new ExecutionContext {
    def reportFailure(t: Throwable) { t.printStackTrace() }
    def execute(runnable: Runnable) {runnable.run()}
  }

  object Implicits {
    /**
     * Runs in the caller's thread.
     */
    implicit val sameThreadContext = Execution.sameThreadContext
  }

}
