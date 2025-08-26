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
import cats.effect.testkit.TestControl
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.duration._

class TimedPullsSuite extends Fs2Suite {

  def fail(s: String) = Pull.raiseError[IO](new Exception(s))

  test("behaves as a normal Pull when no timeouts are used") {
    forAllF { (s: Stream[Pure, Int]) =>
      val prog =
        s.covary[IO]
          .pull
          .timed { tp =>
            def loop(tp: Pull.Timed[IO, Int]): Pull[IO, Int, Unit] =
              tp.uncons.flatMap {
                case None                   => Pull.done
                case Some((Right(c), next)) => Pull.output(c) >> loop(next)
                case Some((Left(_), _))     => fail("unexpected timeout")
              }

            loop(tp)
          }
          .stream

      prog.assertEmitsSameAs(s)
    }
  }

  test("pulls elements with timeouts, no timeouts trigger") {
    forAllF { (s: Stream[Pure, Int]) =>
      val period = 500.millis
      val timeout = 600.millis
      val expected = s.compile.toList
      val prog =
        s.covary[IO]
          .metered(period)
          .pull
          .timed { tp =>
            def loop(tp: Pull.Timed[IO, Int]): Pull[IO, Int, Unit] =
              tp.uncons.flatMap {
                case None                   => Pull.done
                case Some((Right(c), next)) => Pull.output(c) >> tp.timeout(timeout) >> loop(next)
                case Some((Left(_), _))     => fail("unexpected timeout")
              }

            tp.timeout(timeout) >> loop(tp)
          }
          .stream
          .compile
          .toList

      TestControl.executeEmbed(prog).assertEquals(expected)
    }
  }

  test("times out whilst pulling a single element") {
    val prog = Stream
      .sleep[IO](300.millis)
      .pull
      .timed { tp =>
        tp.timeout(100.millis) >>
          tp.uncons.flatMap {
            case Some((Left(_), _)) => Pull.done
            case _                  => fail("timeout expected")
          }
      }
      .stream
      .compile
      .drain

    TestControl.executeEmbed(prog)
  }

  test("times out after pulling multiple elements") {
    val expected = List(1, 2, 3)
    val s = Stream.emits(expected) ++ Stream.never[IO]
    val t = 100.millis
    val timeout = 350.millis

    val prog = s
      .metered(t)
      .pull
      .timed { tp =>
        def go(tp: Pull.Timed[IO, Int]): Pull[IO, Int, Unit] =
          tp.uncons.flatMap {
            case Some((Right(c), n)) => Pull.output(c) >> go(n)
            case Some((Left(_), _))  => Pull.done
            case None                => fail("Unexpected end of input")
          }

        tp.timeout(timeout) >> go(tp)
      }
      .stream
      .compile
      .toList

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  test("pulls elements with timeouts, timeouts trigger after reset") {
    val timeout = 500.millis
    val t = 600.millis
    val n = 10L
    val s = Stream.constant(1).metered[IO](t).take(n)
    val expected = Stream("timeout", "elem").repeat.take(n * 2).compile.toList
    val prog =
      s.pull
        .timed { tp =>
          def go(tp: Pull.Timed[IO, Int]): Pull[IO, String, Unit] =
            tp.uncons.flatMap {
              case None                   => Pull.done
              case Some((Right(_), next)) =>
                Pull.output1("elem") >> tp.timeout(timeout) >> go(next)
              case Some((Left(_), next)) => Pull.output1("timeout") >> go(next)
            }

          tp.timeout(timeout) >> go(tp)
        }
        .stream
        .compile
        .toList

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  test("timeout can be reset before triggering") {
    val s =
      Stream.emit(()) ++
        Stream.sleep[IO](1.second) ++
        Stream.sleep[IO](1.second) ++
        // use `never` to test logic without worrying about termination
        Stream.never[IO]

    val prog =
      s.pull
        .timed { one =>
          one.timeout(900.millis) >> one.uncons.flatMap {
            case Some((Right(_), two)) =>
              two.timeout(1100.millis) >> two.uncons.flatMap {
                case Some((Right(_), three)) =>
                  three.uncons.flatMap {
                    case Some((Left(_), _)) => Pull.done
                    case _                  => fail(s"Expected timeout third, received element")
                  }
                case _ => fail(s"Expected element second, received timeout")
              }
            case _ => fail(s"Expected element first, received timeout")
          }

        }
        .stream
        .compile
        .drain

    TestControl.executeEmbed(prog)
  }

  test("timeout can be reset to a shorter one") {
    val s =
      Stream.emit(()) ++
        Stream.sleep[IO](1.second) ++
        Stream.never[IO]

    val prog =
      s.pull
        .timed { one =>
          one.timeout(2.seconds) >> one.uncons.flatMap {
            case Some((Right(_), two)) =>
              two.timeout(900.millis) >> two.uncons.flatMap {
                case Some((Left(_), _)) => Pull.done
                case _                  => fail(s"Expected timeout second, received element")
              }
            case _ => fail(s"Expected element first, received timeout")
          }
        }
        .stream
        .compile
        .drain

    TestControl.executeEmbed(prog)
  }

  test("timeout can be reset without starting a new one") {
    val s = Stream.sleep[IO](2.seconds) ++ Stream.sleep[IO](2.seconds)
    val t = 3.seconds
    val prog =
      s.pull
        .timed { one =>
          one.timeout(t) >> one.uncons.flatMap {
            case Some((Right(_), two)) =>
              two.timeout(0.millis) >>
                two.uncons.flatMap {
                  case Some((Right(_), three)) =>
                    three.uncons.flatMap {
                      case None => Pull.done
                      case v    => fail(s"Expected end of stream, received $v")
                    }
                  case _ => fail("Expected element second, received timeout")
                }
            case _ => fail("Expected element first, received timeout")
          }
        }
        .stream
        .compile
        .drain

    TestControl.executeEmbed(prog)
  }

  test("never emits stale timeouts") {
    val t = 200.millis

    val prog =
      (Stream.sleep[IO](t) ++ Stream.never[IO]).pull
        .timed { tp =>
          def go(tp: Pull.Timed[IO, Unit]): Pull[IO, String, Unit] =
            tp.uncons.flatMap {
              case None                => Pull.done
              case Some((Right(_), n)) =>
                Pull.output1("elem") >>
                  tp.timeout(0.millis) >> // cancel old timeout without starting a new one
                  go(n)
              case Some((Left(_), n)) =>
                Pull.output1("timeout") >> go(n)
            }

          tp.timeout(t) >> // race between timeout and stream waiting
            go(tp)
        }
        .stream
        .interruptAfter(3.seconds)
        .compile
        .toList

    def check(results: List[String]): IO[Unit] = {
      val validInterleavings = Set(List("timeout", "elem"), List("elem"))
      // we canceled the timeout after receiving an element, so this
      // interleaving breaks the invariant that an old timeout can never
      // be unconsed after timeout has reset it
      val buggyInterleavings = Set(List("elem", "timeout"))

      if (validInterleavings.contains(results))
        IO.unit
      else if (buggyInterleavings.contains(results))
        IO.raiseError(new Exception("A stale timeout was received"))
      else
        IO.raiseError(new Exception("Unexpected error"))
    }

    TestControl.executeEmbed(
      prog
        .flatMap(check)
        .replicateA(10) // number of iterations to stress the race
    )
  }

  test(
    "A timeout called before the very first uncons starts simultaneously with the first uncons"
  ) {
    val emissionTime = 100.millis
    val timeout = 200.millis
    val timedPullPause = Pull.eval(IO.sleep(150.millis))

    val prog =
      Stream
        .sleep[IO](emissionTime)
        .pull
        .timed { tp =>
          tp.timeout(timeout) >>
            // If the first timeout started immediately, this pause
            // before uncons would cause a timeout to be emitted
            timedPullPause >>
            tp.uncons.flatMap {
              case Some((Right(_), _)) =>
                Pull.done
              case Some((Left(_), _)) =>
                fail("Unexpected timeout")
              case None =>
                fail("Unexpected end of stream")
            }
        }
        .stream
        .compile
        .drain

    TestControl.executeEmbed(prog)
  }

  test("After the first uncons, timeouts start immediately") {
    val emissionTime = 100.millis
    val timeout = 200.millis
    val timedPullPause = Pull.eval(IO.sleep(150.millis))

    val prog =
      Stream
        .sleep[IO](emissionTime)
        .repeatN(2)
        .pull
        .timed { tp =>
          tp.timeout(timeout) >>
            // If the first timeout started immediately, this pause
            // before uncons would cause a timeout to be emitted
            timedPullPause >>
            tp.uncons.flatMap {
              case Some((Right(_), tp)) =>
                tp.timeout(timeout) >>
                  // The timeout starts immediately, so this pause
                  // before uncons causes a timeout
                  timedPullPause >>
                  tp.uncons.flatMap {
                    case Some((Left(_), _)) =>
                      Pull.done
                    case Some((Right(_), _)) =>
                      fail("Unexpected element, expected timeout")
                    case None =>
                      fail("Unexpected end of stream")
                  }
              case Some((Left(_), _)) =>
                fail("Unexpected timeout")
              case None =>
                fail("Unexpected end of stream")
            }
        }
        .stream
        .compile
        .drain

    TestControl.executeEmbed(prog)
  }
}
