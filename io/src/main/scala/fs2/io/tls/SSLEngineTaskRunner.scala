package fs2
package io
package tls

import javax.net.ssl.SSLEngine

import cats.Applicative
import cats.effect.{Blocker, Concurrent, ContextShift}
import cats.implicits._

private[io] trait SSLEngineTaskRunner[F[_]] {
  def runDelegatedTasks: F[Unit]
}

private[io] object SSLEngineTaskRunner {
  def apply[F[_]: Concurrent: ContextShift](
      engine: SSLEngine,
      blocker: Blocker
  ): SSLEngineTaskRunner[F] =
    new SSLEngineTaskRunner[F] {
      def runDelegatedTasks: F[Unit] =
        blocker.delay(Option(engine.getDelegatedTask)).flatMap {
          case None       => Applicative[F].unit
          case Some(task) => blocker.delay(task.run) >> runDelegatedTasks
        }
    }
}
