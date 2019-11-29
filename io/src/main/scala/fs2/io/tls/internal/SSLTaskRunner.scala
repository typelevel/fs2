package spinoco.fs2.crypto.internal

import cats.Applicative
import javax.net.ssl.SSLEngine
import cats.effect.{Concurrent, Sync,ContextShift}
import cats.syntax.all._
import simulacrum.typeclass

import scala.concurrent.ExecutionContext

@typeclass
protected[crypto] trait SSLTaskRunner[F[_]] {
  def runTasks: F[Unit]
}


protected[crypto] object SSLTaskRunner {

  def mk[F[_] : Concurrent : ContextShift](engine: SSLEngine, sslEc: ExecutionContext): F[SSLTaskRunner[F]] = Sync[F].delay {

    new SSLTaskRunner[F] {
      def runTasks: F[Unit] =
        implicitly[ContextShift[F]].evalOn(sslEc)(Sync[F].delay { Option(engine.getDelegatedTask)  }).flatMap {
          case None => Applicative[F].unit
          case Some(task) => implicitly[ContextShift[F]].evalOn(sslEc)(Sync[F].delay { task.run() }) >> runTasks
        }
    }
  }

}
