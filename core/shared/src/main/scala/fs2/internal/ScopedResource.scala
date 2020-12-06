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

import cats.effect.kernel.Resource
import cats.implicits._

import fs2.Compiler

/** Represents a resource acquired during stream interpretation.
  *
  * A resource is acquired by `Algebra.Acquire` and then released by `Algebra.CloseScope`.
  *
  * The acquisition of a resource has three steps:
  *
  * 1. A `ScopedResource` instance is created and registered with the current scope (`Algebra.Acquire`)
  * 2. Acquisition action is evaluated
  * 3. `acquired` is invoked to confirm acquisition of the resource
  *
  * The reason for this is that during asynchronous stream evaluation, one scope may close the other scope
  * (e.g., merged stream fails while another stream is still acquiring an asynchronous resource).
  * In such a case, a resource may be `released` before `acquired` was evaluated, resulting
  * in an immediate finalization after acquisition is confirmed.
  *
  * A resource may be released by any of the following methods:
  *
  * (1) The owning scope was closed by `Algebra.CloseScope`. This essentially evaluates `release` of
  *     the `ScopedResource` and acts like (1).
  * (2) `acquired` was evaluated after scope was `released` by either (1) or (2). In this case,
  *     finalizer will be invoked immediately if the resource is not leased.
  * (3) `cancel` is invoked on a `Lease` for the resource. This will invoke the finalizer
  *     if the resource was already acquired and released and there are no other outstanding leases.
  *
  * Scoped resources may be leased to other scopes. Each scope must lease with `lease` and  when the other
  * scope is closed (or when the resource lease is no longer required) release the lease with `Lease#cancel`.
  *
  * Note that every method which may potentially call a resource finalizer returns `F[Either[Throwable, Unit]]`
  * instead of `F[Unit]`` to make sure any errors that occur when releasing the resource are properly handled.
  */
private[fs2] sealed abstract class ScopedResource[F[_]] {

  /** Id of the resource
    */
  def id: Unique

  /** Depending on resource state this will either release resource, or when resource was not yet fully
    * acquired, this will schedule releasing of the resource at earliest opportunity, that is when:
    *
    * (a) The resource finished its acquisition
    *  - and -
    * (b) All scopes borrowing this resource released resource.
    *
    * As a part of release process the resoure's finalizer may be run and in that case it may fail eventually.
    *
    * @return
    */
  def release(ec: Resource.ExitCase): F[Either[Throwable, Unit]]

  /** Signals that resource was successfully acquired.
    *
    * If the resource was closed before being acquired, then supplied finalizer is run.
    * That may result in finalizer eventually failing.
    *
    * Yields to true, if the resource's finalizer was successfully acquired and not run, otherwise to false or an error.
    * If the error is yielded then finalizer was run and failed.
    *
    * @param finalizer Finalizer to be run on release is provided.
    * @return
    */
  def acquired(finalizer: Resource.ExitCase => F[Unit]): F[Either[Throwable, Boolean]]

  /** Signals that this resource was leased by another scope than one allocating this resource.
    *
    * Yields to `Some(lease)`, if this resource was successfully leased, and scope must bind `lease.cancel` it when not needed anymore.
    * or to `None` when this resource cannot be leased because resource is already released.
    */
  def lease: F[Option[Lease[F]]]
}

private[internal] object ScopedResource {

  /** State of the resource.
    *
    * @param open       resource is open. At this state resource is either awating its acquisition
    *                   by invoking the `acquired` or is used by Stream.
    * @param finalizer  When resource is successfully acquired, this will contain finalizer that shall be
    *                   invoked when the resource is released.
    * @param leases     References (leases) of this resource
    */
  private[this] final case class State[+F[_]](
      open: Boolean,
      finalizer: Option[Resource.ExitCase => F[Either[Throwable, Unit]]],
      leases: Int
  ) {
    /* The `isFinished` predicate indicates that the finalizer can be run at the present state:
      which happens IF it is closed, AND there are no acquired leases pending to be released. */
    @inline def isFinished: Boolean = !open && leases == 0
  }

  private[this] val initial = State(open = true, finalizer = None, leases = 0)

  def create[F[_]](implicit F: Compiler.Target[F]): F[ScopedResource[F]] =
    for {
      state <- F.ref[State[F]](initial)
      token <- F.unique
    } yield new ScopedResource[F] {

      override val id: Unique = token

      private[this] val pru: F[Either[Throwable, Unit]] =
        (Right(()): Either[Throwable, Unit]).pure[F]

      def release(ec: Resource.ExitCase): F[Either[Throwable, Unit]] =
        state
          .modify { s =>
            if (s.leases != 0)
              // do not allow to run finalizer if there are leases open
              (s.copy(open = false), None)
            else
              // reset finalizer to None, will be run, it available, otherwise the acquire will take care of it
              (s.copy(open = false, finalizer = None), s.finalizer)
          }
          .flatMap(finalizer => finalizer.map(_(ec)).getOrElse(pru))

      def acquired(finalizer: Resource.ExitCase => F[Unit]): F[Either[Throwable, Boolean]] =
        state.modify { s =>
          if (s.isFinished)
            // state is closed and there are no leases, finalizer has to be invoked right away
            s -> finalizer(Resource.ExitCase.Succeeded).as(false).attempt
          else {
            val attemptFinalizer = (ec: Resource.ExitCase) => finalizer(ec).attempt
            // either state is open, or leases are present, either release or `Lease#cancel` will run the finalizer
            s.copy(finalizer = Some(attemptFinalizer)) -> (Right(true): Either[
              Throwable,
              Boolean
            ]).pure[F]
          }
        }.flatten

      def lease: F[Option[Lease[F]]] =
        state.modify { s =>
          if (s.open)
            s.copy(leases = s.leases + 1) -> Some(TheLease)
          else
            s -> None
        }

      private[this] object TheLease extends Lease[F] {
        def cancel: F[Either[Throwable, Unit]] =
          state
            .modify { s =>
              val now = s.copy(leases = s.leases - 1)
              now -> now
            }
            .flatMap { now =>
              if (now.isFinished)
                state.modify { s =>
                  // Scope is closed and this is last lease, assure finalizer is removed from the state and run
                  // previous finalizer shall be always present at this point, this shall invoke it
                  s.copy(finalizer = None) -> (s.finalizer match {
                    case Some(ff) => ff(Resource.ExitCase.Succeeded)
                    case None     => pru
                  })
                }.flatten
              else
                pru
            }
      }
    }
}
