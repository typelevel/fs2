package fs2

import java.util.concurrent.Executors

import cats.effect.{IO, Resource}
import cats.implicits._

import scala.concurrent.ExecutionContext

trait TestPlatform {

  def isJVM: Boolean = true

  val blockingExecutionContext: Resource[IO, ExecutionContext] =
    Resource
      .make(IO(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))))(ec =>
        IO(ec.shutdown()))
      .widen[ExecutionContext]

}
