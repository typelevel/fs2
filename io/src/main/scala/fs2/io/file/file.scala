package fs2
package io

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.nio.channels.CompletionHandler
import java.nio.file.{ Path, StandardOpenOption, WatchEvent }
import java.util.concurrent.ExecutorService

import cats.effect.{ Effect, IO,Sync }

/** Provides support for working with files. */
package object file {

  /**
   * Provides a handler for NIO methods which require a `java.nio.channels.CompletionHandler` instance.
   */
  private[fs2] def asyncCompletionHandler[F[_], O](f: CompletionHandler[O, Null] => Unit)(implicit F: Effect[F], ec: ExecutionContext): F[O] = {
    F.async[O] { cb =>
      f(new CompletionHandler[O, Null] {
        override def completed(result: O, attachment: Null): Unit =
          async.unsafeRunAsync(F.delay(cb(Right(result))))(_ => IO.pure(()))
        override def failed(exc: Throwable, attachment: Null): Unit =
          async.unsafeRunAsync(F.delay(cb(Left(exc))))(_ => IO.pure(()))
      })
    }
  }

  //
  // Stream constructors
  //

  /**
   * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
   */
  def readAll[F[_]: Sync](path: Path, chunkSize: Int): Stream[F, Byte] =
    pulls.fromPath(path, List(StandardOpenOption.READ)).flatMap(c => pulls.readAllFromFileHandle(chunkSize)(c.resource)).stream

  /**
   * Reads all data asynchronously from the file at the specified `java.nio.file.Path`.
   */
  def readAllAsync[F[_]](path: Path, chunkSize: Int, executorService: Option[ExecutorService] = None)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Byte] =
    pulls.fromPathAsync(path, List(StandardOpenOption.READ), executorService).flatMap(c => pulls.readAllFromFileHandle(chunkSize)(c.resource)).stream

  /**
   * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
   *
   * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
   */
  def writeAll[F[_]: Sync](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)): Sink[F, Byte] =
    in => (for {
      out <- pulls.fromPath(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- pulls.writeAllToFileHandle(in, out.resource)
    } yield ()).stream

  /**
   * Writes all data asynchronously to the file at the specified `java.nio.file.Path`.
   *
   * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
   */
  def writeAllAsync[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE), executorService: Option[ExecutorService] = None)(implicit F: Effect[F], ec: ExecutionContext): Sink[F, Byte] =
    in => (for {
      out <- pulls.fromPathAsync(path, StandardOpenOption.WRITE :: flags.toList, executorService)
      _ <- _writeAll0(in, out.resource, 0)
    } yield ()).stream

  private def _writeAll0[F[_]](in: Stream[F, Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] =
    in.pull.unconsChunk.flatMap {
      case None => Pull.done
      case Some((hd,tl)) => _writeAll1(hd, out, offset) *> _writeAll0(tl, out, offset + hd.size)
    }

  private def _writeAll1[F[_]](buf: Chunk[Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)) flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAll1(buf.drop(written).toOption.get.toChunk, out, offset + written)
    }

  /**
   * Creates a [[Watcher]] for the default file system.
   *
   * A singleton bracketed stream is returned consisting of the single watcher. To use the watcher,
   * `flatMap` the returned stream, watch or register 1 or more paths, and then return `watcher.events()`.
   *
   * @return singleton bracketed stream returning a watcher
   */
  def watcher[F[_]](implicit F: Effect[F], ec: ExecutionContext): Stream[F,Watcher[F]] = Watcher.default

  /**
   * Watches a single path.
   *
   * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
   */
  def watch[F[_]](path: Path, types: Seq[Watcher.EventType] = Nil, modifiers: Seq[WatchEvent.Modifier] = Nil, pollTimeout: FiniteDuration = 1.second)(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Watcher.Event] =
    Watcher.default.flatMap(w => Stream.eval_(w.watch(path, types, modifiers)) ++ w.events(pollTimeout))
}
