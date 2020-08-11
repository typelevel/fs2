// package fs2

// import cats.implicits._
// import cats.effect.{Concurrent, Resource, Sync}
// import cats.effect.concurrent.Ref
// import cats.effect.implicits._

// /**
//   * Supports treating a linear sequence of resources as a single resource.
//   *
//   * A `Hotswap[F, R]` instance is created as a `Resource` and hence, has
//   * a lifetime that is scoped by the `Resource`. After creation, a `Resource[F, R]`
//   * can be swapped in to the `Hotswap` by calling `swap`. The acquired resource
//   * is returned and is finalized when the `Hotswap` is finalized or upon the next
//   * call to `swap`, whichever occurs first.
//   *
//   * For example, the sequence of three resources `r1, r2, r3` are shown in the
//   * following diagram:
//   *
//   * {{{
//   * >----- swap(r1) ---- swap(r2) ---- swap(r3) ----X
//   * |        |             |             |          |
//   * Creation |             |             |          |
//   *         r1 acquired    |             |          |
//   *                       r2 acquired    |          |
//   *                       r1 released   r3 acquired |
//   *                                     r2 released |
//   *                                                r3 released
//   * }}}
//   *
//   * This class is particularly useful when working with pulls that cycle through
//   * resources -- e.g., writing bytes to files, rotating files every N bytes or M seconds.
//   * Without `Hotswap`, such pulls leak resources -- on each file rotation, a file handle
//   * or at least an internal resource reference accumulates. With `Hotswap`, the `Hotswap`
//   * instance is the only registered resource and each file is swapped in to the `Hotswap`.
//   *
//   * Usage typically looks something like:
//   *
//   * {{{
//   * Stream.resource(Hotswap(mkResource)).flatMap { case (hotswap, r) =>
//   *   // Use r, call hotswap.swap(mkResource) as necessary
//   * }
//   * }}}
//   *
//   * See `fs2.io.file.writeRotate` for an example of usage.
//   */
// sealed trait Hotswap[F[_], R] {

//   /**
//     * Allocates a new resource, closes the last one if present, and
//     * returns the newly allocated `R`.
//     *
//     * If there are no further calls to `swap`, the resource created by
//     * the last call will be finalized when the lifetime of
//     * this `Hotswap` (which is itself tracked by `Resource`) is over.
//     *
//     * Since `swap` closes the old resource immediately, you need to
//     * ensure that no code is using the old `R` when `swap` is called.
//     * Failing to do so is likely to result in an error on the
//     * _consumer_ side. In any case, no resources will be leaked by
//     * `swap`.
//     *
//     * If you try to call swap after the lifetime of this `Hotswap` is
//     * over, `swap` will fail, but it will ensure all resources are
//     * closed, and never leak any.
//     */
//   def swap(next: Resource[F, R]): F[R]

//   /**
//     * Runs the finalizer of the current resource, if any, and restores
//     * this `Hotswap` to its initial state.
//     *
//     * Like `swap`, you need to ensure that no code is using the old `R` when
//     * `clear is called`. Similarly, calling `clear` after the lifetime of this
//     * `Hotswap` results in an error.
//     */
//   def clear: F[Unit]
// }

// object Hotswap {

//   /**
//     * Creates a new `Hotswap` initialized with the specified resource.
//     * The `Hotswap` instance and the initial resource are returned.
//     */
//   def apply[F[_]: Concurrent, R](initial: Resource[F, R]): Resource[F, (Hotswap[F, R], R)] =
//     create[F, R].evalMap(p => p.swap(initial).map(r => (p, r)))

//   /**
//     * Creates a new `Hotswap`, which represents a `Resource`
//     * that can be swapped during the lifetime of this `Hotswap`.
//     */
//   def create[F[_]: Concurrent, R]: Resource[F, Hotswap[F, R]] = {
//     def raise[A](msg: String): F[A] =
//       Sync[F].raiseError(new RuntimeException(msg))

//     def initialize = Ref[F].of(().pure[F].some)

//     def finalize(state: Ref[F, Option[F[Unit]]]): F[Unit] =
//       state
//         .getAndSet(None)
//         .flatMap {
//           case None            => raise[Unit]("Finalizer already run")
//           case Some(finalizer) => finalizer
//         }

//     Resource.make(initialize)(finalize).map { state =>
//       new Hotswap[F, R] {
//         override def swap(next: Resource[F, R]): F[R] =
//           // workaround for https://github.com/typelevel/cats-effect/issues/579
//           Concurrent[F].continual((next <* ().pure[Resource[F, *]]).allocated) {
//             r => // this whole block is inside continual and cannot be canceled
//               Sync[F].fromEither(r).flatMap {
//                 case (newValue, newFinalizer) =>
//                   swapFinalizer(newFinalizer).as(newValue)
//               }
//           }

//         override def clear: F[Unit] =
//           swapFinalizer(().pure[F]).uncancelable

//         private def swapFinalizer(newFinalizer: F[Unit]): F[Unit] =
//           state.modify {
//             case Some(oldFinalizer) =>
//               newFinalizer.some -> oldFinalizer
//             case None =>
//               None -> (newFinalizer *> raise[Unit]("Cannot swap after proxy has been finalized"))
//           }.flatten
//       }
//     }
//   }
// }
