package fix

import cats.effect.IO
import fs2._

object Bracket {
  val myResource = Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))
  val myComplexResource = Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.bracket(IO.pure(r + "2"))(_ => IO.unit).flatMap(r2 => Stream.emit(r2)))
  val bracketFor = for {
    r1 <- Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))
    r2 <- Stream.bracket(IO.pure("resource2"))(_ => IO.unit).flatMap(r => Stream.emit(r))
  } yield ()

  for {
    r <- Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))
  } yield r

  println(Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r)))

  (Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r)), Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r)))

  def somewhereABracket: Stream[IO, String] = {
    println("irrelevant")
    val internal = Stream.bracket(IO.pure("internal"))(_ => IO.unit).flatMap(r => Stream.emit(r))
    for {
      r <- Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.bracket(IO.pure(r + "2"))(_ => IO.unit).flatMap(r2 => Stream.emit(r2)))
    } yield r
    Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.bracket(IO.pure(r + "2"))(_ => IO.unit).flatMap(r2 => Stream.emit(r2)))
  }

  def bracketDef: Stream[IO, String] = Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))
  var bracketVar: Stream[IO, String] = Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))

  def bracketDefFor: Stream[IO, String] = for {
    r <- Stream.bracket(IO.pure("resource"))(_ => IO.unit).flatMap(r => Stream.emit(r))
  } yield r
}
