package fs2

import cats.effect.IO

class HotswapSpec extends Fs2Spec {
  "Hotswap" - {
    "finalizer of target run when hotswap is finalized" in {
      Logger[IO].flatMap { logger =>
        Stream
          .resource(Hotswap(logger.logLifecycleR("a")))
          .flatMap(_ => logger.logInfo("using"))
          .compile
          .drain *> logger.get.asserting(it =>
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

    "swap acquires new resource and then finalizes old resource" in {
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
          .drain *> logger.get.asserting(it =>
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

    "clear finalizes old resource" in {
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
          .drain *> logger.get.asserting(it =>
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
}
