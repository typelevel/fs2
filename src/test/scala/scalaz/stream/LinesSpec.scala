package scalaz.stream

import org.scalacheck._
import Prop._

import scalaz.stream.Process._
import scalaz.stream.text.lines

object LinesSpec extends Properties("text.lines") {

  val samples = 0 until 5 flatMap { i => List("\r\n", "\n").map { s =>
    "Hello&World.&Foo&Bar&".replace("&", s*i)
    }
  }

  // behavior should be identical to that of scala.io.Source
  def checkLine(s: String): Boolean = {
    val source = scala.io.Source.fromString(s).getLines().toList
    emitSeq(s.toCharArray.map(_.toString)).pipe(lines).toList == source &&
      emit(s).pipe(lines).toList == source
  }

  property("Line endings") = secure {
    samples.foldLeft(true)((b, s2) => b && checkLine(s2))
  }
}
