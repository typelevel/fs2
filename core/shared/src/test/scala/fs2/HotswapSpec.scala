package fs2

import cats.implicits._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.effect.IO
import cats.effect.Resource

class HotswapSpec extends Fs2Spec {
  "Hotswap" - {
    "finalizer of target run when hotswap is finalized" in {
      mkEventLogger.flatMap { logger =>
        Stream
          .resource(Hotswap(logLifecycle(logger, "a")))
          .flatMap { _ =>
            Stream
              .eval_(logger.log(Info("using")))
          }
          .compile
          .drain *> logger.get.asserting(
          _ shouldBe List(
            Acquired("a"),
            Info("using"),
            Released("a")
          )
        )
      }
    }

    "swap acquires new resource and then finalizes old resource" in {
      mkEventLogger.flatMap { logger =>
        Stream
          .resource(Hotswap(logLifecycle(logger, "a")))
          .flatMap {
            case (hotswap, _) =>
              Stream.eval_(logger.log(Info("using a"))) ++
                Stream.eval_(hotswap.swap(logLifecycle(logger, "b"))) ++
                Stream.eval_(logger.log(Info("using b"))) ++
                Stream.eval_(hotswap.swap(logLifecycle(logger, "c"))) ++
                Stream.eval_(logger.log(Info("using c")))
          }
          .compile
          .drain *> logger.get.asserting(
          _ shouldBe List(
            Acquired("a"),
            Info("using a"),
            Acquired("b"),
            Released("a"),
            Info("using b"),
            Acquired("c"),
            Released("b"),
            Info("using c"),
            Released("c")
          )
        )
      }
    }

    "clear finalizes old resource" in {
      mkEventLogger.flatMap { logger =>
        Stream
          .resource(Hotswap(logLifecycle(logger, "a")))
          .flatMap {
            case (hotswap, _) =>
              Stream.eval_(logger.log(Info("using a"))) ++
                Stream.eval_(hotswap.clear) ++
                Stream.eval_(logger.log(Info("after clear")))
          }
          .compile
          .drain *> logger.get.asserting(
          _ shouldBe List(
            Acquired("a"),
            Info("using a"),
            Released("a"),
            Info("after clear")
          )
        )
      }
    }
  }

  trait Logger[F[_], A] {
    def log(a: A): F[Unit]
    def get: F[List[A]]
  }
  object Logger {
    def apply[F[_]: Sync, A]: F[Logger[F, A]] =
      Ref.of(Nil: List[A]).map { ref =>
        new Logger[F, A] {
          def log(a: A): F[Unit] = ref.update(acc => a :: acc)
          def get: F[List[A]] = ref.get.map(_.reverse)
        }
      }
  }

  sealed trait Event
  case class Acquired(tag: String) extends Event
  case class Released(tag: String) extends Event
  case class Info(message: String) extends Event

  def mkEventLogger: IO[Logger[IO, Event]] = Logger[IO, Event]

  def logLifecycle(logger: Logger[IO, Event], tag: String): Resource[IO, Unit] =
    Resource.make(logger.log(Acquired(tag)))(_ => logger.log(Released(tag)))
}
