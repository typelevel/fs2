package fs2.internal

import java.util.concurrent.atomic._

import Ref._

/**
 * A reference which may be updated transactionally, without
 * use of `==` on `A`.
 */
private[fs2]
class Ref[A](id: AtomicLong, ref: AtomicReference[A], lock: ReadWriteSpinLock) {

  /**
   * Obtain a snapshot of the current value, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access: (A, A => Boolean) = {
    lock.startRead
    val i = id.get
    val a = ref.get
    lock.finishRead
    val s = set(i)
    // from this point forward, only one thread may write `ref`
    (a, s)
  }

  private def set(expected: Long): A => Boolean = a => {
    id.get == expected && {
      lock.startWrite // don't bother acquiring lock if no chance of succeeding
      try {
        if (id.compareAndSet(expected, expected+1)) { ref.set(a); true }
        else false
      }
      finally { lock.finishWrite; () }
    }
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

private[fs2]
object Ref {

  class ReadWriteSpinLock(readers: AtomicLong) { // readers = -1 means writer has lock

    /** Increment the reader count. Fails only due to contention with a writer. */
    @annotation.tailrec
    final def tryStartRead: Boolean = {
      val cur = readers.get
      if (cur >= 0) {
        if (!readers.compareAndSet(cur,cur+1)) tryStartRead
        else true
      }
      else false // lost due to writer contention
    }

    /** Decrement the reader count, possibly unblocking waiting writers
     *  if the count is now 0. */
    final def finishRead: Unit = { readers.decrementAndGet; () }

    /** Repeatedly [[tryStartRead]] until it succeeds. */
    @annotation.tailrec
    final def startRead: Unit = if (!tryStartRead) startRead

    /** Try obtaining the write lock, failing only due to other writer contention. */
    private final def tryStartWrite: Boolean = readers.compareAndSet(0,-1)

    /** Obtain the write lock. */
    @annotation.tailrec
    final def startWrite: Unit = if (!tryStartWrite) startWrite

    /** Release the write lock, unblocking both readers and other writers. */
    final def finishWrite: Boolean = readers.compareAndSet(-1,0)
  }

  def apply[A](a: A): Ref[A] =
    new Ref(new AtomicLong(0), new AtomicReference(a),
            new ReadWriteSpinLock(new AtomicLong(0)))
}
