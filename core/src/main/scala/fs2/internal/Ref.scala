package fs2.internal

import java.util.concurrent.atomic._

/** A reference which may be updated atomically, without use of `==` on `A`. */
private[fs2] class Ref[A](id: AtomicLong, ref: AtomicReference[A]) {
  def access: (A, A => Boolean) = {
    val s = set(id.get)
    // from this point forward, only one thread may write `ref`
    (ref.get, s)
  }
  def get: A = ref.get
  def modify(f: A => A): Boolean = tryModify(f andThen (Some(_)))

  /** Like `modify`, but `f` may choose not to modify. */
  def tryModify(f: A => Option[A]): Boolean = access match {
    case (a, set) => f(a).map(set).getOrElse(false)
  }

  /** Like `tryModify`, but retry if failure due to contention. */
  @annotation.tailrec
  final def repeatedlyTryModify(f: A => Option[A]): Boolean = access match {
    case (a, set) => f(a) match {
      case None => false
      case Some(a) => set(a) || repeatedlyTryModify(f)
    }
  }
  private def set(expected: Long): A => Boolean = a => {
    if (id.compareAndSet(expected, expected+1)) { ref.set(a); true }
    else false
  }
}

