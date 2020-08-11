package fs2
package io
package tls

import javax.net.ssl.SSLEngine

import cats.Applicative
import cats.effect.Sync
import cats.implicits._

private[tls] trait SSLEngineTaskRunner[F[_]] {
  def runDelegatedTasks: F[Unit]
}

private[tls] object SSLEngineTaskRunner {
  def apply[F[_]](
      engine: SSLEngine
  )(implicit F: Sync[F]): SSLEngineTaskRunner[F] =
    new SSLEngineTaskRunner[F] {
      def runDelegatedTasks: F[Unit] =
        F.blocking(Option(engine.getDelegatedTask)).flatMap {
          case None       => Applicative[F].unit
          case Some(task) => F.blocking(task.run) >> runDelegatedTasks
        }
    }
}
