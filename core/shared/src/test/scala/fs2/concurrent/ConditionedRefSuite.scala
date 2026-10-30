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
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.concurrent.duration._

class ConditionedRefSuite extends Fs2Suite {

  test("waitUntil completes immediately if the condition already holds") {
    TestControl.executeEmbed {
      ConditionedRef.of[IO, Int](initial = 1).flatMap { ref =>
        ref.waitUntil(_ > 0).timed.map { case (elapsed, _) => assertEquals(elapsed, Duration.Zero) }
      }
    }
  }

  test("waitUntil completes on the update that makes the condition true") {
    TestControl.executeEmbed {
      ConditionedRef.of[IO, Int](initial = 0).flatMap { ref =>
        val updates =
          IO.sleep(1.second) >> ref.update(_ + 1) >> IO.sleep(1.second) >> ref.update(_ + 1)
        updates.background.surround {
          ref.waitUntil(_ >= 2).timed.map { case (elapsed, _) => assertEquals(elapsed, 2.seconds) }
        }
      }
    }
  }

  test("waitUntil does not miss a condition that becomes true and then false again") {
    TestControl.executeEmbed {
      ConditionedRef.of[IO, Int](initial = 0).flatMap { ref =>
        ref.waitUntil(_ == 1).start.flatMap { waiter =>
          IO.sleep(1.second) >> ref.set(1) >> ref.set(2) >>
            waiter.joinWithNever.timeout(1.second) >> ref.get.assertEquals(2)
        }
      }
    }
  }

  test("only waiters whose condition holds are woken") {
    TestControl.executeEmbed {
      ConditionedRef.of[IO, Int](initial = 0).flatMap { ref =>
        (ref.waitUntil(_ >= 1).start, ref.waitUntil(_ >= 2).start).flatMapN { (first, second) =>
          IO.sleep(1.second) >> ref.set(1) >>
            first.joinWithNever.timeout(1.second) >>
            IO.race(second.joinWithNever, IO.sleep(1.second)).map(r => assert(r.isRight)) >>
            ref.set(2) >>
            second.joinWithNever.timeout(1.second)
        }
      }
    }
  }

  test("a cancelled waiter does not affect later updates and waiters") {
    TestControl.executeEmbed {
      ConditionedRef.of[IO, Int](initial = 0).flatMap { ref =>
        ref.waitUntil(_ > 0).timeoutTo(duration = 1.second, fallback = IO.unit) >>
          ref.update(_ + 1) >>
          ref.waitUntil(_ > 0).timeout(1.second) >>
          ref.get.assertEquals(1)
      }
    }
  }
}
