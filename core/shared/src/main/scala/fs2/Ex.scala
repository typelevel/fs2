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

object Ex {
  import cats.effect.IO
  import cats.effect.unsafe.implicits.global
  import scala.concurrent.duration._
  import cats.effect.implicits._
  import cats.syntax.all._



  val s = (Stream("elem") ++ Stream.sleep_[IO](200.millis)).repeat.take(5)
  def e =
    s.pull
      .timed { timedPull =>
        def go(timedPull: Pull.Timed[IO, String]): Pull[IO, String, Unit] =
          timedPull.timeout(150.millis) >> Pull.eval(IO.println("starting timeout")) >> // starts new timeout and stops the previous one
        timedPull.uncons.flatMap {
          case Some((Right(elems), next)) => Pull.output(elems) >> go(next)
          case Some((Left(_), next)) => Pull.output1("late!") >> go(next)
          case None => Pull.done
        }
        go(timedPull)
      }.stream.debug().compile.toVector.flatMap(IO.println).unsafeToFuture()

  def e2 =
    Stream(Stream.exec(IO.println("inner")) ++ Stream.sleep[IO](400.millis))
      .repeat
      .take(10)
      .covary[IO]
      .metered(200.millis)
      .switchMap(x => x)
      .debug(v => s"outer")
      .compile
      .drain
      .unsafeToFuture()

  def e3 =
    concurrent.SignallingRef[IO, (String, FiniteDuration)]("" -> 0.millis)
      .flatMap { sig =>
        sig
          .discrete
          .dropWhile(_._1 == "")
          .switchMap { case (name, duration) =>
            Stream.exec(IO.println(s"received $name")) ++
            Stream.sleep[IO](duration).as(name)
          }.debug(v => s"emitted $v")
          .compile
          .drain
          .background
          .use { _ =>
            sig.set("foo", 150.millis) >> IO.sleep(200.millis) >> IO.println("elem") >>
            sig.set("bar", 150.millis) >> IO.sleep(200.millis) >> IO.println("elem") >>
            sig.set("baz", 150.millis) >> IO.sleep(200.millis) >> IO.println("elem")
          }
      }.unsafeToFuture()
}
