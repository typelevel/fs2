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

import cats.effect.IO
import munit.{CatsEffectSuite, DisciplineSuite, Location, ScalaCheckEffectSuite}
import scala.reflect.ClassTag

abstract class Fs2Suite
    extends CatsEffectSuite
    with DisciplineSuite
    with ScalaCheckEffectSuite
    with TestPlatform
    with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  override def munitFlakyOK = true

  /** Returns a stream that has a 10% chance of failing with an error on each output value. */
  protected def spuriousFail[F[_]: RaiseThrowable, O](s: Stream[F, O]): Stream[F, O] =
    Stream.suspend {
      val counter = new java.util.concurrent.atomic.AtomicLong(0L)
      s.flatMap { o =>
        val i = counter.incrementAndGet
        if (i % (math.random() * 10 + 1).toInt == 0L) Stream.raiseError[F](new Err)
        else Stream.emit(o)
      }
    }

  protected def group(name: String)(thunk: => Unit): Unit = {
    val countBefore = munitTestsBuffer.size
    val _ = thunk
    val countAfter = munitTestsBuffer.size
    val countRegistered = countAfter - countBefore
    val registered = munitTestsBuffer.toList.drop(countBefore)
    (0 until countRegistered).foreach(_ => munitTestsBuffer.remove(countBefore))
    registered.foreach(t => munitTestsBuffer += t.withName(s"$name - ${t.name}"))
  }

  implicit class StreamAssertionsOf[A](val str: Stream[IO, A]) {

    def assertEmpty(): IO[Unit] =
      str.compile.toList.assertEquals(Nil)

    def assertEmits(expectedOutputs: List[A]): IO[Unit] =
      str.compile.toList.assertEquals(expectedOutputs)

    def assertForall(pred: A => Boolean): IO[Unit] =
      str.compile.toList.map(ls => assert(ls.forall(pred)))

    def assertEmitsSameAs(expectedOutputs: Stream[IO, A]): IO[Unit] =
      for {
        actual <- str.compile.toList
        expect <- expectedOutputs.compile.toList
      } yield assertEquals(actual, expect)

    def assertEmitsUnordered(expectedOutputs: Set[A]): IO[Unit] =
      str.compile.toList.map(_.toSet).assertEquals(expectedOutputs)

    def assertEmitsUnordered(expectedOutputs: List[A]): IO[Unit] =
      str.compile.toList.map(_.toSet).assertEquals(expectedOutputs.toSet)

    def assertEmitsUnorderedSameAs(expected: Stream[IO, A]): IO[Unit] =
      for {
        actual <- str.compile.toList
        expect <- expected.compile.toList
      } yield assertEquals(actual.toSet, expect.toSet)

    def intercept[T <: Throwable](implicit T: ClassTag[T], loc: Location): IO[T] =
      str.compile.drain.intercept[T]
  }

  implicit class PureStreamAssertions[A](val str: Stream[Pure, A]) {
    def assertEmits(expectedOutputs: List[A]): Unit =
      assertEquals(str.toList, expectedOutputs)

    def assertForall(pred: A => Boolean): Unit =
      assert(str.toList.forall(pred))
  }

}
