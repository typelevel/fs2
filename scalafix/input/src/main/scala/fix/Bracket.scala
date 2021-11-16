/*
rule = v1
 */
package fix

import cats.effect.IO
import fs2._

object Bracket {
  val myResource = Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)
  val myComplexResource = Stream.bracket(IO.pure("resource"))(r => Stream.bracket(IO.pure(r+"2"))(r2 => Stream.emit(r2), _ => IO.unit), _ => IO.unit)
  val bracketFor = for {
    r1 <- Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)
    r2 <- Stream.bracket(IO.pure("resource2"))(r => Stream.emit(r), _ => IO.unit)
  } yield ()

  for {
    r <- Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)
  } yield r

  println(Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit))

  (Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit), Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit))

  def somewhereABracket: Stream[IO, String] = {
    println("irrelevant")
    val internal = Stream.bracket(IO.pure("internal"))(r => Stream.emit(r), _ => IO.unit)
    for {
      r <- Stream.bracket(IO.pure("resource"))(r => Stream.bracket(IO.pure(r+"2"))(r2 => Stream.emit(r2), _ => IO.unit), _ => IO.unit)
    } yield r
    Stream.bracket(IO.pure("resource"))(r => Stream.bracket(IO.pure(r+"2"))(r2 => Stream.emit(r2), _ => IO.unit), _ => IO.unit)
  }

  def bracketDef: Stream[IO, String] = Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)
  var bracketVar: Stream[IO, String] = Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)

  def bracketDefFor: Stream[IO, String] = for {
    r <- Stream.bracket(IO.pure("resource"))(r => Stream.emit(r), _ => IO.unit)
  } yield r
}
