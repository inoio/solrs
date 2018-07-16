package io.ino.solrs

import scala.util.Failure
import scala.util.Success
import scala.util.Try

object Utils {

  implicit class OptionOps[A](opt: Option[A]) {

    def toTry(msg: String): Try[A] = {
      opt
        .map(Success(_))
        .getOrElse(Failure(new NoSuchElementException(msg)))
    }
  }

}