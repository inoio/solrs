package io.ino.time

/**
 * Abstraction for the current time. Might be replaced with java.time.Clock (java 8) in the future.
 */
trait Clock {

  def millis(): Long

}

object Clock {

  def systemDefault = new SystemClock

  class SystemClock extends Clock {
    override def millis(): Long = System.currentTimeMillis()
  }

  def mutable = new MutableClock

  class MutableClock extends Clock {

    private var _millis: Long = 0

    override def millis(): Long = _millis

    def set(millis: Long): Unit = {
      _millis = millis
    }

    def advance(by: Long): Unit = {
      _millis += by
    }

    def resetToZero(): Unit = {
      _millis = 0
    }

    def resetToSystemMillis(): Unit = {
      _millis = System.currentTimeMillis()
    }

  }

}