package fs2

import cats.effect.IO
import cats.effect.laws.util.TestContext
import cats.implicits._
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.Laws
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import cats.effect.Timer
import cats.effect.ContextShift

abstract class Fs2Suite extends ScalaCheckSuite with TestPlatform with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  protected val testContext = FunFixture[TestContext](setup = _ => TestContext(), teardown = _ => ())

  implicit val realExecutionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global
  implicit val timerIO: Timer[IO] = IO.timer(realExecutionContext)
  implicit val contextShiftIO: ContextShift[IO] =
    IO.contextShift(realExecutionContext)

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

  private def samples[X](g: Gen[X]): List[X] = {
    def go(acc: List[X], rem: Int): List[X] = {
      if (rem <= 0) acc.reverse
      else g.sample match {
        case Some(x) => go(x :: acc, rem - 1)
        case None => go(acc, rem)
      }
    }
    go(Nil, scalaCheckTestParameters.minSuccessfulTests)
  }

  def forAllAsync[A](f: A => IO[Unit])(implicit arbA: Arbitrary[A]): Future[Unit] =
    samples(arbA.arbitrary).traverse_(f).unsafeToFuture

  def forAllAsync[A, B](f: (A, B) => IO[Unit])(implicit arbA: Arbitrary[A], arbB: Arbitrary[B]): Future[Unit] = {
    samples(arbA.arbitrary).zip(samples(arbB.arbitrary)).traverse_ {
      case (a, b) => f(a, b)
    }.unsafeToFuture
  }

  def forAllAsync[A, B, C](f: (A, B, C) => IO[Unit])(implicit arbA: Arbitrary[A], arbB: Arbitrary[B], arbC: Arbitrary[C]): Future[Unit] = {
    samples(arbA.arbitrary).zip(samples(arbB.arbitrary)).zip(samples(arbC.arbitrary)).traverse_ {
      case ((a, b), c) => f(a, b, c)
    }.unsafeToFuture
  }
}
