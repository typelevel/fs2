package fs2.async

import cats.{Eq, Show}
import cats.implicits._

/**
 * The result of a modification to a [[Ref]] or [[SyncRef]].
 *
 * `previous` is the value before modification (i.e., the value passed to modify
 * function, `f` in the call to `modify(f)`. `now` is the new value computed by `f`.
 */
final case class Change[+A](previous: A, now: A) {
  def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
}

object Change {

  implicit def eqInstance[A: Eq]: Eq[Change[A]] =
    Eq.by(c => c.previous -> c.now)

  implicit def showInstance[A: Show]: Show[Change[A]] =
    Show(c => show"Change(previous: ${c.previous}, now: ${c.now})")
}
