/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.Functor
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._

trait Logger[F[_]] {
  def log(e: LogEvent): F[Unit]

  def logInfo(msg: String): Stream[F, Nothing] = Stream.eval_(log(LogEvent.Info(msg)))

  def logLifecycle(tag: String)(implicit F: Functor[F]): Stream[F, Unit] =
    Stream.resource(logLifecycleR(tag))

  def logLifecycleR(tag: String)(implicit F: Functor[F]): Resource[F, Unit] =
    Resource.make(log(LogEvent.Acquired(tag)))(_ => log(LogEvent.Released(tag)))

  def get: F[List[LogEvent]]
}

object Logger {
  def apply[F[_]: Sync]: F[Logger[F]] =
    Ref.of(Nil: List[LogEvent]).map { ref =>
      new Logger[F] {
        def log(e: LogEvent): F[Unit] = ref.update(acc => e :: acc)
        def get: F[List[LogEvent]] = ref.get.map(_.reverse)
      }
    }
}

sealed trait LogEvent
object LogEvent {
  final case class Acquired(tag: String) extends LogEvent
  final case class Released(tag: String) extends LogEvent
  final case class Info(message: String) extends LogEvent
}
