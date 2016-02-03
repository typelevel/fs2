package fs2.internal

import java.util.concurrent.atomic._

/**
 * A reference which may be updated transactionally, without
 * use of `==` on `A`.
 */
private[fs2] class Ref[A](id: AtomicLong, ref: AtomicReference[A]) {

  /**
   * Obtain a snapshot of the current value, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access: (A, A => Boolean) = {
    val i = id.get
    val s = set(i)
    // from this point forward, only one thread may write `ref`
    (ref.get, s)
  }

  private def set(expected: Long): A => Boolean = a => {
    if (id.compareAndSet(expected, expected+1)) { ref.set(a); true }
    else false
  }

  def get: A = ref.get

  /**
   * Attempt a single transactional update, returning `false`
   * if the set loses due to contention.
   */
  def modify1(f: A => A): Boolean = possiblyModify1(f andThen (Some(_)))

  /**
   * Transactionally modify the value.
   * `f` may be called multiple times, until this modification wins
   * the race with any concurrent modifications.
   */
  @annotation.tailrec
  final def modify(f: A => A): Unit =
    if (!modify1(f)) modify(f)

  /**
   * Like `modify`, but `f` may choose not to modify. Unlike
   * just using `modify1(identity)`, if `f` returns `None` this
   * generates no contention.
   */
  def possiblyModify1(f: A => Option[A]): Boolean = access match {
    case (a, set) => f(a).map(set).getOrElse(false)
  }

  override def toString = "Ref { "+ref.get.toString+" }"
}

object Ref {
  def apply[A](a: A): Ref[A] =
    new Ref(new AtomicLong(0), new AtomicReference(a))
}
