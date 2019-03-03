package fs2

import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import scala.concurrent.duration._

object Continual extends IOApp {

  def run(args: List[String]) = ExitCode.Success.pure[IO]

  // `F[A]` can be canceled, but if it completes, `Err \/ A => F[B]` happen too.
  //
  // Cannot use `bracket` because that makes `F[A]` uncancelable
  // Cannot use `guarantee` because you don't have access to `A`
  // Cannot use `flatMap` cause you can be interrupted between `F[A]` and `Err \/ A => F[B]`
  // Cannot use `fa.flatMap(fb).uncancelable` because that makes `fa` uncancelable too
  def continual[F[_]: Concurrent, A, B](cancelable: F[A])(
      continuation: Either[Throwable, A] => F[B]): F[B] =
    Deferred[F, Unit].product(Deferred[F, Unit]).flatMap {
      case (stop, finaliserDone) =>
        val action = cancelable.attempt.race(stop.get).flatMap {
          case Left(a)  => continuation(a).map(_.some)
          case Right(_) => Option.empty[B].pure[F]
        } <* finaliserDone.complete(())

        action.start.bracket(fiber =>
          (fiber.join.map(_.get)).guaranteeCase {
            case ExitCase.Canceled =>
              stop.complete(()) >> finaliserDone.get // this is uncancelable
            case _ => ().pure[F]
        })(_ => ().pure[F])
    }

  def runner(n: Int)(io: IO[Unit]) =
    io.timeout(n.seconds)
      .guarantee(IO(println("done")))
      .handleError(_ => ())
      .unsafeRunSync

  def faIsInterruptible() = runner(1) {
    continual(IO(println("start")) >> IO.never)(_ => IO.unit)
  }

  def finaliserBackPressures() = runner(1) {
    def fa = (IO(println("start")) >> IO.never).guaranteeCase {
      case ExitCase.Canceled => timer.sleep(2.seconds) >> IO(println("finaliser done"))
      case _                 => IO.unit
    }

    continual(fa)(_ => IO.unit)
  }

  def noInterruptionHappyPath() =
    continual(IO(1))(n => IO(n.right.get + 1)).map(_ + 1).unsafeRunSync

  def noInterruptionError() =
    continual(IO.raiseError(new Exception("boom")))(n => IO(n.left.get)).unsafeRunSync

  def noInterruptionContinuationError =
    continual(IO(1))(_ => IO.raiseError(new Exception("boom"))).attempt.unsafeRunSync

  def continualSemantics() = runner(1) {
    continual(IO(println("start")))(_ => timer.sleep(2.seconds) >> IO(println("continuation done")))
  }
// scala> import Continual._
// import Continual._

// scala> faIsInterruptible
// start
// done

// scala> finaliserBackPressures
// start
// finaliser done
// done

// scala> noInterruptionHappyPath
// res2: Int = 3

// scala> noInterruptionError
// res3: Throwable = java.lang.Exception: boom

// scala> noInterruptionContinuationError
// res4: Either[Throwable,Nothing] = Left(java.lang.Exception: boom)

// scala> continualSemantics
// start
// continuation done
// done

}
