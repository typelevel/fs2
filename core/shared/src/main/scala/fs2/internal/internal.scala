package fs2

import cats.effect.ExitCase

package object internal {

  /* A Finaliser[F[_]] is an effectful operation, invoked upon the termination of a process,
   * to clean up some resources. */
  private[internal] type Finaliser[F[_]] = ExitCase[Throwable] => F[Unit]

  /* A Cleanup[R, F[_]] is an effectful operation, that takes a resource and a termination signal,
   * and performs a side-effectful operation to cleanup that resource  */
  private[internal] type Cleanup[R, F[_]] = (R, ExitCase[Throwable]) => F[Unit]
}
