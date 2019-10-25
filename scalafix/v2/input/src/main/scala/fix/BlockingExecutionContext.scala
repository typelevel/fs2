/*
rule = v2
 */
package fix

import java.nio.channels.FileChannel
import java.nio.file.{Path, Paths}

import cats.effect.{Concurrent, ContextShift, IO, Sync, Resource}
import fs2.{Stream, io, text}
import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import cats.instances.string.catsStdShowForString
import fs2.io.Watcher

object BlockingExecutionContext {
  val ec = ExecutionContext.global
  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  val blockingExecutionContext: Resource[IO, ExecutionContextExecutorService] =
    Resource.make(IO(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))(ec => IO(ec.shutdown()))

  val path = Paths.get("testdata/fahrenheit.txt")

  Stream.resource(blockingExecutionContext)
  def genResource[F[_]]: Resource[F,ExecutionContext] = ???
  def makeResourceF[F[_]](implicit S: Sync[F]) = Stream.resource(genResource[F])
  def makeResourceH[F[_], G[_], H[_]](implicit S: Sync[H]) = Stream.resource(genResource[H])
  def makeResourceG[G[_]](implicit S: Sync[G]) = Stream.resource(genResource[G])

  val stream = Stream.eval(IO.pure("foo"))
  stream.linesAsync(Console.out, ec)
  stream.showLinesAsync(Console.out, ec)
  stream.showLinesStdOutAsync(ec)

  io.file.readAll[IO](path, ec, 4096)
  def readAll[F[_]](implicit S: Sync[F], CS: ContextShift[F]): Stream[F, Byte] = io.file.readAll[F](path, ec, 4096)
  def readAllX[X[_]](implicit S: Sync[X], CS: ContextShift[X]): Stream[X, Byte] = io.file.readAll[X](path, ec, 4096)

  io.file.writeAll[IO](Paths.get("testdata/celsius.txt"), ec)
  def writeAll[F[_]](implicit S: Sync[F], CS: ContextShift[F]): fs2.Pipe[F, Byte, Unit] = io.file.writeAll[F](Paths.get("testdata/celsius.txt"), ec)
  def writeAllX[X[_]](implicit S: Sync[X], CS: ContextShift[X]): fs2.Pipe[X, Byte, Unit] = io.file.writeAll[X](Paths.get("testdata/celsius.txt"), ec)

  io.file.watcher[IO]
  def watcher[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Resource[F, Watcher[F]] = io.file.watcher[F]
  def watcherX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Resource[X, Watcher[X]] = io.file.watcher[X]

  io.file.watch[IO](path)
  def watch[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Stream[F, Watcher.Event] = io.file.watch[F](path)
  def watchX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Stream[X, Watcher.Event] = io.file.watch[X](path)

  io.file.watch[IO](path, Nil , Nil)
  def watchWithMoreParam[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Stream[F, Watcher.Event] = io.file.watch[F](path, Nil , Nil)
  def watchWithMoreParamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Stream[X, Watcher.Event] = io.file.watch[X](path, Nil , Nil)

  io.file.pulls.fromPath[IO](path, ec, Nil)
  def fromPath[F[_]](implicit C: Concurrent[F], CS: ContextShift[F])= io.file.pulls.fromPath[F](path, ec, Nil)
  def fromPathX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.file.pulls.fromPath[X](path, ec, Nil)

  io.file.pulls.fromFileChannel[IO](IO(FileChannel.open(path)), ec)
  def fromFileChannel[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) =  io.file.pulls.fromFileChannel[F](C.delay(FileChannel.open(path)), ec)
  def fromFileChannelX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) =  io.file.pulls.fromFileChannel[X](C.delay(FileChannel.open(path)), ec)

  io.readInputStream[IO](???, 1024, ec)
  def readInputStream[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.readInputStream[F](???, 1024, ec)
  def readInputStreamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.readInputStream[X](???, 1024, ec)

  io.unsafeReadInputStream[IO](???, 1024, ec)
  def unsafeReadInputStream[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.unsafeReadInputStream[F](???, 1024, ec)
  def unsafeReadInputStreamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.unsafeReadInputStream[X](???, 1024, ec)

  io.file.readRange[IO](path, ec, 1,2,3)
  def readRange[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.file.readRange[F](path, ec, 1,2,3)
  def readRangeX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.file.readRange[X](path, ec, 1,2,3)

  io.stdin[IO](1024, ec)
  def stdin[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdin[F](1024, ec)
  def stdinX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdin[X](1024, ec)

  io.stdout[IO](ec)
  def stdout[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdout[F](ec)
  def stdoutX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdout[X](ec)

  io.stdoutLines[IO, String](ec)
  def stdoutLines[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdoutLines[F, String](ec)
  def stdoutLinesX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdoutLines[X, String](ec)

  io.stdinUtf8[IO](1024, ec)
  def stdinUtf8[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdinUtf8[F](1024, ec)
  def stdinUtf8X[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdinUtf8[X](1024, ec)

  Watcher.default[IO]
  def default[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) =  Watcher.default[F]
  def defaultX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) =  Watcher.default[X]

  def readAllGen[G[_]](implicit S: Sync[G], CS: ContextShift[G]) =
    io.file.readAll[G](path, ec, 4096)

  def fahrenheitToCelsius(f: Double): Double =
    (f - 32.0) * (5.0/9.0)

  val converter: Stream[IO, Unit] = Stream.resource(blockingExecutionContext).flatMap { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  val converterXX: Stream[IO, Unit] =
    for {
      blocker <- Stream.resource(blockingExecutionContext)
      _ <- io.file.readAll[IO](path, blocker, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .through(text.utf8Encode)
        .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
    } yield ()

  val converterXXX: Stream[IO, Unit] =
    for {
      blocker <- Stream.resource[IO, ExecutionContextExecutorService](blockingExecutionContext)
      _ <- io.file.readAll[IO](path, blocker, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .through(text.utf8Encode)
        .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
    } yield ()

  val converter2: Stream[IO, Unit] = Stream.resource[IO, ExecutionContextExecutorService](blockingExecutionContext).flatMap { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  val converter3: Stream[IO, Unit] = Stream.resource(blockingExecutionContext).map { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  val converter4: Stream[IO, Unit] = Stream.resource[IO, ExecutionContextExecutorService](blockingExecutionContext).map { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

}
