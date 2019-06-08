package fs2

import scala.concurrent.ExecutionContext

import cats.effect.{IO, Resource}
import cats.implicits._

import java.util.concurrent.Executors

import fs2.internal.ThreadFactories

trait TestPlatform {

  def isJVM: Boolean = true

  val blockingExecutionContext: Resource[IO, ExecutionContext] =
    Resource
      .make(
        IO(ExecutionContext.fromExecutorService(
          Executors.newCachedThreadPool(ThreadFactories.named("fs2-blocking", true)))))(ec =>
        IO(ec.shutdown()))
      .widen[ExecutionContext]

}
