package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{IO, Resource}
import cats.implicits._

import java.util.concurrent.{Executors, TimeoutException}

trait TestUtilPlatform {

  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global

  val blockingExecutionContext: Resource[IO, ExecutionContext] =
    Resource
      .make(IO(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))))(ec =>
        IO(ec.shutdown()))
      .widen[ExecutionContext]

  def isJVM: Boolean = true

  def runLog[A](s: Stream[IO, A])(implicit timeout: FiniteDuration): Vector[A] =
    s.compile.toVector
      .unsafeRunTimed(timeout)
      .getOrElse(throw new TimeoutException("IO run timed out"))

  def throws[A](err: Throwable)(s: Stream[IO, A]): Boolean =
    s.compile.toVector.attempt.unsafeRunSync() match {
      case Left(e) if e == err => true
      case Left(e)             => println(s"EXPECTED: $err, thrown: $e"); false
      case _                   => false
    }
}
