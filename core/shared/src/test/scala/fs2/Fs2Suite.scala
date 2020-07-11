package fs2

import cats.data.State
import cats.effect.{ContextShift, IO, Sync, Timer}
import cats.effect.laws.util.TestContext
import cats.implicits._
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.rng.Seed
import org.typelevel.discipline.Laws
import scala.concurrent.{ExecutionContext, Future}
import munit.Location

abstract class Fs2Suite extends ScalaCheckSuite with TestPlatform with Generators {

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

  private def samples[X](
      g: Gen[X],
      params: Gen.Parameters = Gen.Parameters.default
  ): State[Seed, List[X]] = {
    def go(acc: List[X], rem: Int, seed: Seed): (Seed, List[X]) =
      if (rem <= 0) (seed, acc.reverse)
      else {
        val r = g.doPureApply(params, seed)
        r.retrieve match {
          case Some(x) => go(x :: acc, rem - 1, r.seed)
          case None    => go(acc, rem, r.seed)
        }
      }
    State(s => go(Nil, scalaCheckTestParameters.minSuccessfulTests, s))
  }

  private def reportPropertyFailure(f: IO[Unit], seed: Seed, describe: => String)(implicit
      loc: Location
  ): IO[Unit] =
    f.handleErrorWith { t =>
      fail(s"Property failed with seed ${seed.toBase64} and params: " + describe, t)
    }

  def forAllAsync[A](f: A => IO[Unit])(implicit arbA: Arbitrary[A], loc: Location): Future[Unit] = {
    val seed = Seed.random()
    samples(arbA.arbitrary)
      .runA(seed)
      .value
      .traverse_(a => reportPropertyFailure(f(a), seed, a.toString))
      .unsafeToFuture
  }

  def forAllAsync[A, B](
      f: (A, B) => IO[Unit]
  )(implicit arbA: Arbitrary[A], arbB: Arbitrary[B], loc: Location): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
    } yield as.zip(bs)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case (a, b) => reportPropertyFailure(f(a, b), seed, s"($a, $b)")
      }
      .unsafeToFuture
  }

  def forAllAsync[A, B, C](f: (A, B, C) => IO[Unit])(implicit
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C],
      loc: Location
  ): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
      cs <- samples(arbC.arbitrary)
    } yield as.zip(bs).zip(cs)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case ((a, b), c) => reportPropertyFailure(f(a, b, c), seed, s"($a, $b, $c)")
      }
      .unsafeToFuture
  }

  def forAllAsync[A, B, C, D](f: (A, B, C, D) => IO[Unit])(implicit
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C],
      arbD: Arbitrary[D],
      loc: Location
  ): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
      cs <- samples(arbC.arbitrary)
      ds <- samples(arbD.arbitrary)
    } yield as.zip(bs).zip(cs).zip(ds)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case (((a, b), c), d) => reportPropertyFailure(f(a, b, c, d), seed, s"($a, $b, $c, $d)")
      }
      .unsafeToFuture
  }
}
