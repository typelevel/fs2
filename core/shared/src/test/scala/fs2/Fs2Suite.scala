package fs2

import scala.concurrent.{ExecutionContext, Future}

import cats.effect.{ContextShift, IO, Sync, SyncIO, Timer}
import cats.implicits._
import munit.{Location, ScalaCheckSuite}
import org.typelevel.discipline.Laws

abstract class Fs2Suite
    extends ScalaCheckSuite
    with AsyncPropertySupport
    with TestPlatform
    with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  override def munitFlakyOK = true

  val executionContext: ExecutionContext = ExecutionContext.global
  implicit val timerIO: Timer[IO] = IO.timer(executionContext)
  implicit val contextShiftIO: ContextShift[IO] =
    IO.contextShift(executionContext)

  // Shim to make Dotty happy
  implicit val ioConcurrentEffect: cats.effect.ConcurrentEffect[IO] =
    IO.ioConcurrentEffect(contextShiftIO)

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
    super.munitValueTransforms ++ List(munitIOTransform, munitSyncIOTransform)

  // From https://github.com/scalameta/munit/pull/134
  private val munitIOTransform: ValueTransform =
    new ValueTransform("IO", { case e: IO[_] => e.unsafeToFuture() })

  private val munitSyncIOTransform: ValueTransform =
    new ValueTransform(
      "SyncIO",
      { case e: SyncIO[_] => Future(e.unsafeRunSync())(executionContext) }
    )
}
