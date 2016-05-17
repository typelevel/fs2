package fs2.io

import java.nio.channels.{CompletionHandler}
import java.nio.file.{Path, StandardOpenOption}

import fs2._
import fs2.util.Monad

package object file {

  /**
    * Provides a handler for NIO methods which require a `java.nio.channels.CompletionHandler` instance.
    */
  private[fs2] def asyncCompletionHandler[F[_], O](f: CompletionHandler[O, Null] => F[Unit])(implicit F: Async[F], S: Strategy): F[O] = {
    F.async[O] { cb =>
      f(new CompletionHandler[O, Null] {
        override def completed(result: O, attachment: Null): Unit = S(cb(Right(result)))
        override def failed(exc: Throwable, attachment: Null): Unit = S(cb(Left(exc)))
      })
    }
  }

  //
  // Stream constructors
  //

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]](path: Path, chunkSize: Int)(implicit F: Monad[F]): Stream[F, Byte] =
    pulls.fromPath(path, List(StandardOpenOption.READ)).flatMap(pulls.readAllFromFileHandle(chunkSize)).run

  /**
    * Reads all data asynchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAllAsync[F[_]](path: Path, chunkSize: Int)(implicit F: Async[F], S: Strategy): Stream[F, Byte] =
    pulls.fromPathAsync(path, List(StandardOpenOption.READ)).flatMap(pulls.readAllFromFileHandle(chunkSize)).run

  //
  // Stream transducers
  //

  /**
    * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Monad[F]): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- pulls.fromPath(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- pulls.writeAllToFileHandle(in, out)
    } yield ()).run

  /**
    * Writes all data asynchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAllAsync[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Async[F], S: Strategy): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- pulls.fromPathAsync(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- pulls.writeAllToFileHandle(in, out)
    } yield ()).run
}
