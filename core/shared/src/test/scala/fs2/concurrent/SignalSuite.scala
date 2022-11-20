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
package concurrent

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._
import cats.effect.testkit.TestControl
// import cats.laws.discipline.{ApplicativeTests, FunctorTests}
import scala.concurrent.duration._
import org.scalacheck.effect.PropF.forAllF

class SignalSuite extends Fs2Suite {
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(10)
      .withWorkers(1)

  def waitFor(predicate: IO[Boolean]): IO[Unit] =
    predicate.flatMap(passed => if (passed) IO.unit else IO.sleep(5.millis) >> waitFor(predicate))

  test("get/set/discrete") {
    forAllF { (vs0: List[Long]) =>
      val vs = vs0.map(n => if (n == 0) 1 else n)
      SignallingRef[IO, Long](0L).flatMap { s =>
        Ref.of[IO, Long](0).flatMap { r =>
          val publisher = s.discrete.evalMap(r.set)
          val consumer = vs.traverse { v =>
            s.set(v) >> waitFor(s.get.map(_ == v)) >> waitFor(
              r.get.flatMap(rval =>
                if (rval == 0) IO.pure(true)
                else waitFor(r.get.map(_ == v)).as(true)
              )
            )
          }
          Stream.eval(consumer).concurrently(publisher).compile.drain
        }
      }
    }
  }

  test("lens - get/set/discrete") {
    case class Foo(bar: Long, baz: Long)
    object Foo {
      def get(foo: Foo): Long = foo.bar
      def set(foo: Foo)(bar: Long): Foo = foo.copy(bar = bar)
    }

    forAllF { (vs0: List[Long]) =>
      val vs = vs0.map(n => if (n == 0) 1 else n)
      SignallingRef[IO].of(Foo(0L, -1L)).flatMap { s =>
        val l = SignallingRef.lens(s)(Foo.get, Foo.set)
        Ref.of[IO, Foo](Foo(0L, -1L)).flatMap { r =>
          val publisher = s.discrete.evalMap(r.set)
          val consumer = vs.traverse { v =>
            l.set(v) >> waitFor(l.get.map(_ == v)) >> waitFor(
              r.get.flatMap(rval =>
                if (rval == Foo(0L, -1L)) IO.pure(true)
                else waitFor(r.get.map(_ == Foo(v, -1L))).as(true)
              )
            )
          }
          Stream.eval(consumer).concurrently(publisher).compile.drain
        }
      }
    }
  }

  test("mapref - get/set/discrete") {
    forAllF { (vs0: List[Option[Long]]) =>
      val vs = vs0.map(_.map(n => if (n == 0) 1 else n))
      SignallingMapRef.ofSingleImmutableMap[IO, Unit, Long](Map(() -> 0L)).map(_(())).flatMap { s =>
        Ref.of[IO, Option[Long]](Some(0)).flatMap { r =>
          val publisher = s.discrete.evalMap(r.set)
          val consumer = vs.traverse { v =>
            s.set(v) >> waitFor(s.get.map(_ == v)) >> waitFor(
              r.get.flatMap(rval =>
                if (rval == Some(0)) IO.pure(true)
                else waitFor(r.get.map(_ == v)).as(true)
              )
            )
          }
          Stream.eval(consumer).concurrently(publisher).compile.drain
        }
      }
    }
  }

  test("discrete") {
    // verifies that discrete always receives the most recent value, even when updates occur rapidly
    forAllF { (v0: Long, vsTl: List[Long]) =>
      val vs = v0 :: vsTl
      SignallingRef[IO, Long](0L).flatMap { s =>
        Ref.of[IO, Long](0L).flatMap { r =>
          val publisherR = s.discrete.evalMap(i => IO.sleep(10.millis) >> r.set(i))
          val publisherS = vs.traverse(s.set)
          val last = vs.last
          val consumer = waitFor(r.get.map(_ == last))
          Stream
            .eval(consumer)
            .concurrently(publisherR)
            .concurrently(Stream.eval(publisherS))
            .compile
            .drain
        }
      }
    }
  }

  test("mapref - discrete") {
    // verifies that discrete always receives the most recent value, even when updates occur rapidly
    forAllF { (v0: Option[Long], vsTl: List[Option[Long]]) =>
      val vs = v0 :: vsTl
      SignallingMapRef.ofSingleImmutableMap[IO, Unit, Long](Map(() -> 0L)).map(_(())).flatMap { s =>
        Ref.of[IO, Option[Long]](Some(0L)).flatMap { r =>
          val publisherR = s.discrete.evalMap(i => IO.sleep(10.millis) >> r.set(i))
          val publisherS = vs.traverse(s.set)
          val last = vs.last
          val consumer = waitFor(r.get.map(_ == last))
          Stream
            .eval(consumer)
            .concurrently(publisherR)
            .concurrently(Stream.eval(publisherS))
            .compile
            .drain
        }
      }
    }
  }

  test("access cannot be used twice") {
    for {
      s <- SignallingRef[IO, Long](0L)
      access <- s.access
      (v, set) = access
      v1 = v + 1
      v2 = v1 + 1
      r1 <- set(v1)
      r2 <- set(v2)
      r3 <- s.get
    } yield {
      assert(r1)
      assert(!r2)
      assertEquals(r3, v1)
    }
  }

  test("mapref - access cannot be used twice") {
    for {
      s <- SignallingMapRef.ofSingleImmutableMap[IO, Unit, Long](Map(() -> 0L)).map(_(()))
      access <- s.access
      (v, set) = access
      v1 = v.map(_ + 1)
      v2 = v1.map(_ + 1)
      r1 <- set(v1)
      r2 <- set(v2)
      r3 <- s.get
    } yield {
      assert(r1)
      assert(!r2)
      assertEquals(r3, v1)
    }
  }

  test("access updates discrete") {
    SignallingRef[IO, Int](0).flatMap { s =>
      def cas: IO[Unit] =
        s.access.flatMap { case (v, set) =>
          set(v + 1).ifM(IO.unit, cas)
        }

      def updates =
        s.discrete.takeWhile(_ != 1).compile.drain

      updates.start.flatMap { fiber =>
        cas >> fiber.join.timeout(5.seconds)
      }
    }
  }

  test("mapref - access updates discrete") {
    SignallingMapRef.ofSingleImmutableMap[IO, Unit, Int](Map(() -> 0)).map(_(())).flatMap { s =>
      def cas: IO[Unit] =
        s.access.flatMap { case (v, set) =>
          set(v.map(_ + 1)).ifM(IO.unit, cas)
        }

      def updates =
        s.discrete.takeWhile(_ != Some(1)).compile.drain

      updates.start.flatMap { fiber =>
        cas >> fiber.join.timeout(5.seconds)
      }
    }
  }

  test("holdOption") {
    val s = Stream.range(1, 10).covary[IO].holdOption
    s.compile.drain
  }

  test("waitUntil") {
    val target = 5
    val expected = 1

    val prog =
      Stream
        .iterate(0)(_ + 1)
        .covary[IO]
        .metered(100.millis)
        .holdResource(0)
        .use { state =>
          // `poll` is in IO because a Stream implementation would
          // approximate the implementation of waitUntil itself too
          // closely
          def poll(iterations: Int = 0): IO[Int] =
            state.get.flatMap { n =>
              if (n > target) iterations.pure[IO]
              else state.waitUntil(_ > target) >> poll(iterations + 1)
            }

          poll()
        }

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  // TODO - Port laws tests once we have a compatible version of cats-laws
  // /**
  //   * This is unsafe because the Signal created cannot have multiple consumers
  //   * of its discrete stream since the source stream is restarted for each
  //   * consumer.
  //   *
  //   * This allows for things like checking whether two Signals converge to the
  //   * same value, which is important for [[unsafeSignalEquality]].
  //   *
  //   * We use this to create finite Signals for testing, namely Signals whose
  //   * discrete streams terminate and whose gets stop changing after their source
  //   * streams terminate. Using the usual noneTerminate trick (in this case you'd
  //   * make the underlying Signal work on Options of values and then
  //   * unNoneTerminate the discrete stream) makes testing Applicatives painful
  //   * because it's hard to capture what the last get of the Signal should've
  //   * been, which we need to ensure that Signals are converging to the same
  //   * value, since the last get just gets overwritten with a None. So we use
  //   * this instead.
  //   */
  // private def unsafeHold[F[_]: Sync, A](initial: A, source: Stream[F, A]): F[Signal[F, A]] =
  //   Ref.of[F, A](initial).map { ref =>
  //     new Signal[F, A] {
  //       override def discrete: Stream[F, A] =
  //         Stream(initial) ++ source.evalTap(ref.set)

  //       override def continuous: Stream[F, A] = Stream.repeatEval(get)

  //       override def get: F[A] = ref.get
  //     }
  //   }

  // /**
  //   * In order to generate a Signal we have to effectfully run a stream so we
  //   * need an unsafeRunSync here.
  //   */
  // private implicit def unsafeSignalrbitrary[A: Arbitrary]: Arbitrary[Signal[IO, A]] = {
  //   val gen = for {
  //     firstElem <- Arbitrary.arbitrary[A]
  //     finiteElems <- Arbitrary.arbitrary[List[A]]
  //   } yield {
  //     val finiteStream = Stream.emits(finiteElems).covary[IO]
  //     unsafeHold(firstElem, finiteStream)
  //   }
  //   Arbitrary(gen.map(_.unsafeRunSync()))
  // }

  // private type SignalIO[A] = Signal[IO, A]

  // /**
  //   * We need an instance of Eq for the Discipline laws to work, but actually
  //   * running a Signal is effectful, so we have to resort to unsafely
  //   * performing the effect inside the equality check to have a valid Eq
  //   * instance.
  //   *
  //   * Moreover, equality of Signals is kind of murky. Since the exact discrete
  //   * stream and gets that you see are non-deterministic even if two observers
  //   * are looking at the same Signal, we need some notion of equality that is
  //   * robust to this non-determinism.
  //   *
  //   * We say that two Signals are equal if they converge to the same value. And
  //   * two streams converge to the same value if:
  //   * (1) the "last" element of their discrete streams match after a specified
  //   *     test timeout, calling get after
  //   * (2) the last element of the discrete stream results in a match with the
  //   *     last element
  //   * (3) the first (or any) element of the continuous stream called after the "last"
  //   *     element of the discrete stream also matches.
  //   */
  // private implicit def unsafeSignalEquality[A: Eq]: Eq[SignalIO[A]] =
  //   new Eq[SignalIO[A]] {
  //     private val timeout = 250.milliseconds
  //     override def eqv(x: SignalIO[A], y: SignalIO[A]): Boolean = {
  //       val action = for {
  //         lastDiscreteXFiber <- x.discrete.interruptAfter(timeout).compile.last.map(_.get).start
  //         lastDiscreteYFiber <- y.discrete.interruptAfter(timeout).compile.last.map(_.get).start
  //         lastDiscreteX <- lastDiscreteXFiber.join
  //         lastDiscreteY <- lastDiscreteYFiber.join
  //         retrievedX <- x.get
  //         retrievedY <- y.get
  //         aContinuousX <- x.continuous.head.compile.last.map(_.get)
  //         aContinuousY <- y.continuous.head.compile.last.map(_.get)
  //       } yield {
  //         val lastDiscretesAreSame = Eq[A].eqv(lastDiscreteX, lastDiscreteY)
  //         val lastGetsAreTheSame = Eq[A].eqv(retrievedX, retrievedY)
  //         val continuousAfterGetIsTheSame = Eq[A].eqv(aContinuousX, aContinuousY)
  //         val lastDiscreteAgreesWithGet = Eq[A].eqv(lastDiscreteX, retrievedX)
  //         val continuousAfterGetAgreesWithGet = Eq[A].eqv(aContinuousX, retrievedX)

  //         lastDiscretesAreSame &&
  //         lastGetsAreTheSame &&
  //         continuousAfterGetIsTheSame &&
  //         lastDiscreteAgreesWithGet &&
  //         continuousAfterGetAgreesWithGet
  //       }
  //       action.unsafeRunSync()
  //     }
  //   }

  // checkAll(
  //   "Signal (stand-alone functor instance)",
  //   FunctorTests[SignalIO](Signal.functorInstance).functor[String, Int, Double]
  // )

  // checkAll(
  //   "Signal",
  //   ApplicativeTests[SignalIO].applicative[String, Int, Double]
  // )
}
