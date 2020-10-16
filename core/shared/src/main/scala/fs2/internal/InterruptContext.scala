/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.internal

import cats.{Applicative, Id}
import cats.effect.{Concurrent, Outcome}
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.implicits._
import cats.syntax.all._
import InterruptContext.InterruptionOutcome

/** A context of interruption status. This is shared from the parent that was created as interruptible to all
  * its children. It assures consistent view of the interruption through the stack
  * @param concurrent   Concurrent, used to create interruption at Eval.
  *                 If signalled with None, normal interruption is signalled. If signaled with Some(err) failure is signalled.
  * @param ref      When None, scope is not interrupted,
  *                 when Some(None) scope was interrupted, and shall continue with `whenInterrupted`
  *                 when Some(Some(err)) scope has to be terminated with supplied failure.
  * @param interruptRoot Id of the scope that is root of this interruption and is guaranteed to be a parent of this scope.
  *                      Once interrupted, this scope must be closed and pull must be signalled to provide recovery of the interruption.
  * @param cancelParent  Cancels listening on parent's interrupt.
  */
final private[fs2] case class InterruptContext[F[_]](
    deferred: Deferred[F, InterruptionOutcome],
    ref: Ref[F, Option[InterruptionOutcome]],
    interruptRoot: Token,
    cancelParent: F[Unit]
)(implicit F: Concurrent[F]) { self =>

  def complete(outcome: InterruptionOutcome): F[Unit] =
    ref.update(_.orElse(Some(outcome))).guarantee(deferred.complete(outcome).void)

  /** Creates a [[InterruptContext]] for a child scope which can be interruptible as well.
    *
    * In case the child scope is interruptible, this will ensure that this scope interrupt will
    * interrupt the child scope as well.
    *
    * In any case this will make sure that a close of the child scope will not cancel listening
    * on parent interrupt for this scope.
    *
    * @param interruptible  Whether the child scope should be interruptible.
    * @param newScopeId     The id of the new scope.
    */
  def childContext(
      interruptible: Boolean,
      newScopeId: Token
  ): F[InterruptContext[F]] =
    if (interruptible) {
      self.deferred.get.start.flatMap { fiber =>
        InterruptContext(newScopeId, fiber.cancel).flatMap { context =>
          fiber.join
            .flatMap {
              case Outcome.Succeeded(interrupt) =>
                interrupt.flatMap(i => context.complete(i))
              case Outcome.Errored(t) =>
                context.complete(Outcome.Errored(t))
              case Outcome.Canceled() =>
                context.complete(Outcome.Canceled())
            }
            .start
            .as(context)
        }
      }
    } else copy(cancelParent = Applicative[F].unit).pure[F]

  def eval[A](fa: F[A]): F[Either[InterruptionOutcome, A]] =
    F.race(deferred.get, fa.attempt).map {
      case Right(result) => result.leftMap(Outcome.Errored(_))
      case Left(other)   => Left(other)
    }
}

private[fs2] object InterruptContext {

  type InterruptionOutcome = Outcome[Id, Throwable, Token]

  def apply[F[_]](
      newScopeId: Token,
      cancelParent: F[Unit]
  )(implicit F: Concurrent[F]): F[InterruptContext[F]] =
    for {
      ref <- F.ref[Option[InterruptionOutcome]](None)
      deferred <- F.deferred[InterruptionOutcome]
    } yield InterruptContext[F](
      deferred = deferred,
      ref = ref,
      interruptRoot = newScopeId,
      cancelParent = cancelParent
    )
}
