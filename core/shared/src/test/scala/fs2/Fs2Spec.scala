package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.{Functor, Monad}
import cats.effect.{ContextShift, Fiber, IO, Sync, Timer}
import cats.implicits._

import org.scalatest.{Args, Assertion, Matchers, Status, Succeeded}
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.Span
import org.scalatestplus.scalacheck.Checkers

import org.typelevel.discipline.Laws

abstract class Fs2Spec
    extends AsyncFreeSpec
    with AsyncTimeLimitedTests
    with Matchers
    with GeneratorDrivenPropertyChecks
    with Checkers
    with MiscellaneousGenerators
    with ChunkGenerators
    with StreamGenerators
    with EffectTestSupport
    with TestPlatform {
  implicit val timeout: FiniteDuration = 60.seconds
  val timeLimit: Span = timeout

  implicit val realExecutionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global
  implicit val timerIO: Timer[IO] = IO.timer(realExecutionContext)
  implicit val contextShiftIO: ContextShift[IO] =
    IO.contextShift(realExecutionContext)

  // On the JVM, use the default ScalaTest provided EC for test registration but do
  // not declare it implicit, so that implicit uses pick up `realExecutionContext`.
  // This works around a bug in ScalaTest with AsyncFreeSpec, nested scopes, and
  // intermittent ConcurrentModificationExceptions.
  // On JS, always use `realExecutionContext`, knowing that CMEs cannot occur.
  override val executionContext: ExecutionContext =
    if (isJVM) super.executionContext else realExecutionContext

  lazy val isRunningOnTravis: Boolean = sys.props.get("fs2.test.travis").isDefined

  protected def flickersOnTravis: Assertion =
    if (isRunningOnTravis) pending else Succeeded

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = if (isJVM) 25 else 5, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    // Start a fiber that logs execution of long running tests in order to differentiate
    // hung tests from long tests. Note: on Scala.js, logging can only occur if the test
    // yields execution periodically.
    val loggingFiber: Fiber[IO, Unit] = logEverySoOften(testName, 5.seconds).unsafeRunSync
    try super.runTest(testName, args)
    finally loggingFiber.cancel.unsafeRunSync
  }

  private def logEverySoOften(testName: String, period: FiniteDuration): IO[Fiber[IO, Unit]] =
    IO(System.currentTimeMillis).flatMap { start =>
      def go: IO[Unit] =
        IO.sleep(period) >>
          IO(
            println(
              s"""Waiting for test "$testName" (${System.currentTimeMillis - start} milliseconds)"""
            )
          ) >>
          go
      go.start
    }

  /** Returns a stream that has a 10% chance of failing with an error on each output value. */
  protected def spuriousFail[F[_]: RaiseThrowable, O](s: Stream[F, O]): Stream[F, O] =
    Stream.suspend {
      val counter = new java.util.concurrent.atomic.AtomicLong(0L)
      s.flatMap { o =>
        val i = counter.incrementAndGet
        if (i % (math.random * 10 + 1).toInt == 0L) Stream.raiseError[F](new Err)
        else Stream.emit(o)
      }
    }

  /** Provides various ways to make test assertions on an `F[A]`. */
  implicit class Asserting[F[_], A](private val self: F[A]) {

    /**
      * Asserts that the `F[A]` completes with an `A` which passes the supplied function.
      *
      * @example {{{
      * IO(1).asserting(it => assert(it == 1))
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
        case Left(_: E) => F.pure(Succeeded: Assertion)
        case Left(t) =>
          F.delay(
            fail(
              s"Expected an exception of type ${ct.runtimeClass.getName} but got an exception: $t"
            )
          )
        case Right(a) =>
          F.delay(
            fail(s"Expected an exception of type ${ct.runtimeClass.getName} but got a result: $a")
          )
      }
  }

  implicit class EffectfulAssertionOps[F[_]](private val self: F[Assertion]) {
    def repeatTest(n: Int)(implicit F: Monad[F]): F[Assertion] =
      if (n <= 0) F.pure(Succeeded)
      else
        self.flatMap {
          case Succeeded => repeatTest(n - 1)
          case other     => F.pure(other)
        }
  }

  protected def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for ((id, prop) <- ruleSet.all.properties)
      s"${name}.${id}" in check(prop)

  protected def containSameElements(s1: Seq[_], s2: Seq[_]): Boolean =
    s1.length == s2.length && s1.diff(s2).isEmpty

  protected def leftContainsAllOfRight(s1: Seq[_], s2: Seq[_]): Boolean =
    s2.forall(s1.contains)

}
