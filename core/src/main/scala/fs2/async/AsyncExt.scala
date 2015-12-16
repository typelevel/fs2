package fs2.async

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import fs2.async.AsyncExt.Change
import fs2.internal.Actor
import fs2.util.Task.{Result, Callback}
import fs2.{Strategy, Async}
import fs2.util.{Task, Free}

/** Extends `Async` trait to support (cancellable) atomic modification of `Ref` values. */
trait AsyncExt[F[_]] extends Async[F] {

  /**
   * Modifies `ref` by applying `f` to create computation `F[A]` whose
   * value will be used to modify the `Ref`, once it completes. During evaluation
   * of the `modify`, all other `modify` or `set` calls will block, but `get` calls
   * will still run, receiving the old value passed to `f`.
   *
   * If `ref` is not yet set to any value, this will blocks until `ref` is first set.
   *
   * Calls to `set` or `modify` while this `modify` is running are not guaranteed to
   * be run in any particular order.
   *
   * Law: In the context `modify(r)(f) flatMap { c => ... }`, it is guaranteed that any
   * `get(ref)` will return `c.now` or some newer value (if subsequent modifications take
   * place). That is, the set is guaranteed to be visible when the returned `F` completes.
   */
  def modify[A](ref: Ref[A])(f: A => F[A]): F[Change[A]]

  /**
   * Like `[[modify]]` but allows for interruption using the returned `F[Boolean]`. When
   * run, the `F[Boolean]` interrupts the modification, returning `true` if the modification
   * has not started running and the interruption had an effect, and `false` otherwise.
   *
   * Running the `F[Change[A]]` after cancellation may throw an exception or hang indefinitely.
   */
  def cancellableModify[A](r: Ref[A])(f: A => F[A]):F[(F[Change[A]], F[Boolean])]
}

object AsyncExt {

  /**
   * The result of a `Ref` modification. `previous` contains value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. And `now`
   * is the new value computed by `f`.
   */
  case class Change[+A](previous: A, now: A)
}
