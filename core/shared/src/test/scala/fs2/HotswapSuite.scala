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

class HotswapSuite extends Fs2Suite {
  test("finalizer of target run when hotswap is finalized") {
    Logger[IO].flatMap { logger =>
      Stream
        .resource(Hotswap(logger.logLifecycleR("a")))
        .flatMap(_ => logger.logInfo("using"))
        .compile
        .drain *> logger.get.map(it =>
        assert(
          it == List(
            LogEvent.Acquired("a"),
            LogEvent.Info("using"),
            LogEvent.Released("a")
          )
        )
      )
    }
  }

  test("swap acquires new resource and then finalizes old resource") {
    Logger[IO].flatMap { logger =>
      Stream
        .resource(Hotswap(logger.logLifecycleR("a")))
        .flatMap {
          case (hotswap, _) =>
            logger.logInfo("using a") ++
              Stream.exec(hotswap.swap(logger.logLifecycleR("b"))) ++
              logger.logInfo("using b") ++
              Stream.exec(hotswap.swap(logger.logLifecycleR("c"))) ++
              logger.logInfo("using c")
        }
        .compile
        .drain *> logger.get.map(it =>
        assert(
          it == List(
            LogEvent.Acquired("a"),
            LogEvent.Info("using a"),
            LogEvent.Acquired("b"),
            LogEvent.Released("a"),
            LogEvent.Info("using b"),
            LogEvent.Acquired("c"),
            LogEvent.Released("b"),
            LogEvent.Info("using c"),
            LogEvent.Released("c")
          )
        )
      )
    }
  }

  test("clear finalizes old resource") {
    Logger[IO].flatMap { logger =>
      Stream
        .resource(Hotswap(logger.logLifecycleR("a")))
        .flatMap {
          case (hotswap, _) =>
            logger.logInfo("using a") ++
              Stream.exec(hotswap.clear) ++
              logger.logInfo("after clear")
        }
        .compile
        .drain *> logger.get.map(it =>
        assert(
          it == List(
            LogEvent.Acquired("a"),
            LogEvent.Info("using a"),
            LogEvent.Released("a"),
            LogEvent.Info("after clear")
          )
        )
      )
    }
  }
}
