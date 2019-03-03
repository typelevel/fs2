package fs2

import scala.concurrent.duration._

import cats.Functor
import cats.effect.{ContextShift, IO, Sync, Timer}
import cats.implicits._

import org.typelevel.discipline.Laws
import org.scalatest.{Args, Assertion, AsyncFreeSpec, Matchers, Status, Succeeded}
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.Span

abstract class Fs2Spec
    extends AsyncFreeSpec
    with AsyncTimeLimitedTests
    with Matchers
    with GeneratorDrivenPropertyChecks
    with MiscellaneousGenerators
    with ChunkGenerators
    with StreamGenerators
    with EffectTestSupport
    with TestPlatform {

  implicit val timeout: FiniteDuration = 60.seconds
  val timeLimit: Span = timeout

  implicit val timerIO: Timer[IO] = IO.timer(executionContext)
  implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(executionContext)

  lazy val verbose: Boolean = sys.props.get("fs2.test.verbose").isDefined

  protected def flickersOnTravis: Assertion =
    if (verbose) pending else Succeeded

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    if (verbose) println("Starting " + testName)
    try super.runTest(testName, args)
    finally if (verbose) println("Finished " + testName)
  }

  /** Provides various ways to make test assertions on an `F[A]`. */
  implicit class Asserting[F[_], A](private val self: F[A]) {

    /**
      * Asserts that the `F[A]` completes with an `A` which passes the supplied function.
      *
      * @example {{{
      * IO(1).asserting(_ shouldBe 1)
      * }}}
      */
    def asserting(f: A => Assertion)(implicit F: Sync[F]): F[Assertion] =
      self.flatMap(a => F.delay(f(a)))

    /**
      * Asserts that the `F[A]` completes with an `A` and no exception is thrown.
      */
    def assertNoException(implicit F: Functor[F]): F[Assertion] =
      self.as(Succeeded)

    /**
      * Asserts that the `F[A]` fails with an exception of type `E`.
      */
    def assertThrows[E <: Throwable](implicit F: Sync[F], ct: reflect.ClassTag[E]): F[Assertion] =
      self.attempt.flatMap {
        case Left(t: E) => F.pure(Succeeded: Assertion)
        case Left(t) =>
          F.delay(
            fail(
              s"Expected an exception of type ${ct.runtimeClass.getName} but got an exception: $t"))
        case Right(a) =>
          F.delay(
            fail(s"Expected an exception of type ${ct.runtimeClass.getName} but got a result: $a"))
      }
  }
}
