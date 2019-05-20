package fs2

import scala.concurrent.ExecutionContext

import cats.effect.{IO, Resource}
import cats.implicits._

import java.util.concurrent.Executors

trait TestPlatform {

  def isJVM: Boolean = true

  val blockingExecutionContext: Resource[IO, ExecutionContext] =
    Resource
      .make(IO(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))))(ec =>
        IO(ec.shutdown()))
      .widen[ExecutionContext]

}
