package io.ino.time

import scala.language.implicitConversions
import scala.math.Ordering

object Units {

  case class Second(value: Long) extends AnyVal

  // how to get s.th. like `sec1 to sec5` to work?...
  /*with Second.SecondOrdering

  object Second {

    trait SecondOrdering extends AnyVal with Ordering[Second] {
      def compare(x: Second, y: Second) =
        if (x.value < y.value) -1
        else if (x == y) 0
        else 1
    }
    implicit object SecondOrdering extends SecondOrdering


    trait SecondIsIntegral extends Integral[Second] with SecondOrdering {
      def plus(x: Second, y: Second): Second = Second(x.value + y.value)
      def minus(x: Second, y: Second): Second = Second(x.value - y.value)
      def times(x: Second, y: Second): Second = Second(x.value * y.value)
      def quot(x: Second, y: Second): Second = Second(x.value / y.value)
      def rem(x: Second, y: Second): Second = Second(x.value % y.value)
      def negate(x: Second): Second = Second(-x.value)
      def fromInt(x: Int): Second = Second(x.toLong)
      def toInt(x: Second): Int = x.value.toInt
      def toLong(x: Second): Long = x.value
      def toFloat(x: Second): Float = x.value.toFloat
      def toDouble(x: Second): Double = x.value.toDouble
    }
    implicit object SecondIsIntegral extends SecondIsIntegral


  }
  */

  case class Millisecond(value: Long) extends AnyVal

  object Implicits {

    implicit def longToSecond(value: Long): Second = Second(value)    
    
    implicit def longToMillisecond(value: Long): Millisecond = Millisecond(value)

  }

}