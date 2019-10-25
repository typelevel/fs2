package fix

import java.nio.channels.FileChannel
import java.nio.file.{Path, Paths}

import cats.effect.{Concurrent, ContextShift, IO, Sync, Resource}
import fs2.{Stream, io, text}
import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import cats.instances.string.catsStdShowForString
import fs2.io.Watcher
import cats.effect.Blocker

object BlockingExecutionContext {
  val ec = ExecutionContext.global
  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  val blockingExecutionContext: Resource[IO, ExecutionContextExecutorService] =
    Resource.make(IO(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))(ec => IO(ec.shutdown()))

  val path = Paths.get("testdata/fahrenheit.txt")

  Stream.resource(Blocker[IO])
  def genResource[F[_]]: Resource[F,ExecutionContext] = ???
  def makeResourceF[F[_]](implicit S: Sync[F]) = Stream.resource(Blocker[F])
  def makeResourceH[F[_], G[_], H[_]](implicit S: Sync[H]) = Stream.resource(Blocker[H])
  def makeResourceG[G[_]](implicit S: Sync[G]) = Stream.resource(Blocker[G])

  val stream = Stream.eval(IO.pure("foo"))
  stream.linesAsync(Console.out, Blocker.liftExecutionContext(ec))
  stream.showLinesAsync(Console.out, Blocker.liftExecutionContext(ec))
  stream.showLinesStdOutAsync(Blocker.liftExecutionContext(ec))

  io.file.readAll[IO](path, Blocker.liftExecutionContext(ec), 4096)
  def readAll[F[_]](implicit S: Sync[F], CS: ContextShift[F]): Stream[F, Byte] = io.file.readAll[F](path, Blocker.liftExecutionContext(ec), 4096)
  def readAllX[X[_]](implicit S: Sync[X], CS: ContextShift[X]): Stream[X, Byte] = io.file.readAll[X](path, Blocker.liftExecutionContext(ec), 4096)

  io.file.writeAll[IO](Paths.get("testdata/celsius.txt"), Blocker.liftExecutionContext(ec))
  def writeAll[F[_]](implicit S: Sync[F], CS: ContextShift[F]): fs2.Pipe[F, Byte, Unit] = io.file.writeAll[F](Paths.get("testdata/celsius.txt"), Blocker.liftExecutionContext(ec))
  def writeAllX[X[_]](implicit S: Sync[X], CS: ContextShift[X]): fs2.Pipe[X, Byte, Unit] = io.file.writeAll[X](Paths.get("testdata/celsius.txt"), Blocker.liftExecutionContext(ec))

  io.file.watcher[IO](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))
  def watcher[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Resource[F, Watcher[F]] = io.file.watcher[F](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))
  def watcherX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Resource[X, Watcher[X]] = io.file.watcher[X](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))

  io.file.watch[IO](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path)
  def watch[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Stream[F, Watcher.Event] = io.file.watch[F](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path)
  def watchX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Stream[X, Watcher.Event] = io.file.watch[X](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path)

  io.file.watch[IO](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path, Nil, Nil)
  def watchWithMoreParam[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]): Stream[F, Watcher.Event] = io.file.watch[F](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path, Nil, Nil)
  def watchWithMoreParamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]): Stream[X, Watcher.Event] = io.file.watch[X](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())), path, Nil, Nil)

  io.file.FileHandle.fromPath[IO](path, Blocker.liftExecutionContext(ec), Nil)
  def fromPath[F[_]](implicit C: Concurrent[F], CS: ContextShift[F])= io.file.FileHandle.fromPath[F](path, Blocker.liftExecutionContext(ec), Nil)
  def fromPathX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.file.FileHandle.fromPath[X](path, Blocker.liftExecutionContext(ec), Nil)

  io.file.FileHandle.fromFileChannel[IO](IO(FileChannel.open(path)), Blocker.liftExecutionContext(ec))
  def fromFileChannel[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) =  io.file.FileHandle.fromFileChannel[F](C.delay(FileChannel.open(path)), Blocker.liftExecutionContext(ec))
  def fromFileChannelX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) =  io.file.FileHandle.fromFileChannel[X](C.delay(FileChannel.open(path)), Blocker.liftExecutionContext(ec))

  io.readInputStream[IO](???, 1024, Blocker.liftExecutionContext(ec))
  def readInputStream[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.readInputStream[F](???, 1024, Blocker.liftExecutionContext(ec))
  def readInputStreamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.readInputStream[X](???, 1024, Blocker.liftExecutionContext(ec))

  io.unsafeReadInputStream[IO](???, 1024, Blocker.liftExecutionContext(ec))
  def unsafeReadInputStream[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.unsafeReadInputStream[F](???, 1024, Blocker.liftExecutionContext(ec))
  def unsafeReadInputStreamX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.unsafeReadInputStream[X](???, 1024, Blocker.liftExecutionContext(ec))

  io.file.readRange[IO](path, Blocker.liftExecutionContext(ec), 1,2,3)
  def readRange[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.file.readRange[F](path, Blocker.liftExecutionContext(ec), 1,2,3)
  def readRangeX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.file.readRange[X](path, Blocker.liftExecutionContext(ec), 1,2,3)

  io.stdin[IO](1024, Blocker.liftExecutionContext(ec))
  def stdin[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdin[F](1024, Blocker.liftExecutionContext(ec))
  def stdinX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdin[X](1024, Blocker.liftExecutionContext(ec))

  io.stdout[IO](Blocker.liftExecutionContext(ec))
  def stdout[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdout[F](Blocker.liftExecutionContext(ec))
  def stdoutX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdout[X](Blocker.liftExecutionContext(ec))

  io.stdoutLines[IO, String](Blocker.liftExecutionContext(ec))
  def stdoutLines[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdoutLines[F, String](Blocker.liftExecutionContext(ec))
  def stdoutLinesX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdoutLines[X, String](Blocker.liftExecutionContext(ec))

  io.stdinUtf8[IO](1024, Blocker.liftExecutionContext(ec))
  def stdinUtf8[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) = io.stdinUtf8[F](1024, Blocker.liftExecutionContext(ec))
  def stdinUtf8X[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) = io.stdinUtf8[X](1024, Blocker.liftExecutionContext(ec))

  Watcher.default[IO](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))
  def default[F[_]](implicit C: Concurrent[F], CS: ContextShift[F]) =  Watcher.default[F](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))
  def defaultX[X[_]](implicit C: Concurrent[X], CS: ContextShift[X]) =  Watcher.default[X](Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))

  def readAllGen[G[_]](implicit S: Sync[G], CS: ContextShift[G]) =
    io.file.readAll[G](path, Blocker.liftExecutionContext(ec), 4096)

  def fahrenheitToCelsius(f: Double): Double =
    (f - 32.0) * (5.0/9.0)

  val converter: Stream[IO, Unit] = Stream.resource(Blocker[IO]).flatMap { blocker =>
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
      blocker <- Stream.resource(Blocker[IO])
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
      blocker <- Stream.resource[IO, Blocker](Blocker[IO])
      _ <- io.file.readAll[IO](path, blocker, 4096)
        .through(text.utf8Decode)
        .through(text.lines)
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .through(text.utf8Encode)
        .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
    } yield ()

  val converter2: Stream[IO, Unit] = Stream.resource[IO, Blocker](Blocker[IO]).flatMap { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  val converter3: Stream[IO, Unit] = Stream.resource(Blocker[IO]).map { blocker =>
    io.file.readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  val converter4: Stream[IO, Unit] = Stream.resource[IO, Blocker](Blocker[IO]).map { blocker =>
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
