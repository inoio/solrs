package io.ino.concurrent

import scala.concurrent.ExecutionContext

object Execution {

  val sameThreadContext: ExecutionContext = new ExecutionContext {
    def reportFailure(t: Throwable) : Unit = {
      t.printStackTrace()
    }

    def execute(runnable: Runnable) : Unit = {
      runnable.run()
    }
  }

  object Implicits {
    /**
     * Runs in the caller's thread.
     */
    implicit val sameThreadContext: ExecutionContext = Execution.sameThreadContext
  }

}
