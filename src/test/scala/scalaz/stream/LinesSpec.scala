package scalaz.stream

import java.nio.BufferOverflowException

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.stream.text.lines

object LinesSpec extends Properties("text") {

  val samples = 0 until 5 flatMap { i => List("\r\n", "\n").map { s =>
    "Hello&World.&Foo&Bar&".replace("&", s*i)
    }
  }

  // behavior should be identical to that of scala.io.Source
  def checkLine(s: String): Boolean = {
    val source = scala.io.Source.fromString(s).getLines().toList
    emitAll(s.toCharArray.map(_.toString)).pipe(lines()).toList == source &&
      emit(s).pipe(lines()).toList == source
  }

  property("lines()") = secure {
    samples.foldLeft(true)((b, s2) => b && checkLine(s2))
  }

  property("lines(n) should fail for lines with length greater than n") = secure {
    val error = classOf[java.lang.Exception]

    emit("foo\nbar").pipe(lines(3)).toList == List("foo", "bar")    &&   // OK input
    emitAll(List("foo\n", "bar")).pipe(lines(3)).toList == List("foo", "bar") &&   // OK input
    emitAll(List("foo", "\nbar")).pipe(lines(3)).toList == List("foo", "bar") &&   // OK input
    throws(error){ emit("foo").pipe(lines(2)).run[Task].run }       &&
    throws(error){ emit("foo\nbarr").pipe(lines(3)).run[Task].run } &&
    throws(error){ emit("fooo\nbar").pipe(lines(3)).run[Task].run }
  }
}
