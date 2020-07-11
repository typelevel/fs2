package fs2

import cats.effect.{ContextShift, IO, Sync, Timer}
import cats.effect.laws.util.TestContext
import cats.implicits._
import munit.ScalaCheckSuite
import org.typelevel.discipline.Laws
import scala.concurrent.ExecutionContext
import munit.Location

abstract class Fs2Suite
    extends ScalaCheckSuite
    with AsyncPropertySupport
    with TestPlatform
    with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  protected val testContext =
    FunFixture[TestContext](setup = _ => TestContext(), teardown = _ => ())

  implicit val realExecutionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global
  implicit val timerIO: Timer[IO] = IO.timer(realExecutionContext)
  implicit val contextShiftIO: ContextShift[IO] =
    IO.contextShift(realExecutionContext)

  /** Provides various ways to make test assertions on an `F[A]`. */
  implicit class Asserting[F[_], A](private val self: F[A]) {

    /**
      * Asserts that the `F[A]` fails with an exception of type `E`.
      */
    def assertThrows[E <: Throwable](implicit
        F: Sync[F],
        ct: reflect.ClassTag[E],
        loc: Location
    ): F[Unit] =
      self.attempt.flatMap {
        case Left(_: E) => F.pure(())
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

  protected def group(name: String)(thunk: => Unit): Unit = {
    val countBefore = munitTestsBuffer.size
    val _ = thunk
    val countAfter = munitTestsBuffer.size
    val countRegistered = countAfter - countBefore
    val registered = munitTestsBuffer.toList.drop(countBefore)
    (0 until countRegistered).foreach(_ => munitTestsBuffer.remove(countBefore))
    registered.foreach(t => munitTestsBuffer += t.withName(s"$name - ${t.name}"))
  }

  protected def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for ((id, prop) <- ruleSet.all.properties)
      property(s"${name}.${id}")(prop)

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms :+ munitIOTransform

  // From https://github.com/scalameta/munit/pull/134
  private val munitIOTransform: ValueTransform =
    new ValueTransform("IO", { case e: IO[_] => e.unsafeToFuture() })

}
