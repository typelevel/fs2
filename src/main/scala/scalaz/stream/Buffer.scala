package scalaz.stream

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scalaz.concurrent.{Actor,Strategy,Task}
import scalaz.{\/,-\/,\/-}
import scalaz.\/.{left,right}

/**
 * Control structure for ensuring safety of in-place updates to some
 * underlying mutable data structure of type `RW` (mnemonic 'read/write').
 * `R` is the type of read-only views into this mutable structure.
 *
 * `copy` should make a clone of `RW`, and `copyView` should update
 * `R` _in place_ with an identical copy of itself. `copyViews`
 * should return `true` if in the event of active views, we make
 * copies of the views, or `false` if we wish to make a copy of the
 * `RW`.
 */
class CopyOnWrite[RW,R](
    var readWrite: RW,
    copy: RW => RW,
    copyView: R => Unit,
    read: RW => R,
    copyViews: (RW,Seq[R]) => Boolean) {

  /**
   * Returns an immutable view into this structure, guaranteed
   * to never change even in presence of concurrent updates
   * to the underlying.
   */
  def view: Task[R] =
    Task.async[R](cb => hub ! right((r: R) => cb(right(r))))

  /**
   * Mutates the underlying using the given function, taking care
   * to copy either the views or the underlying.
   */
  def modify(f: RW => Any): Task[Unit] =
    Task.async[Unit](cb => hub ! left((rw: RW) => cb(right(f(rw)))))

  private var views = Vector[WeakReference[R]]()

  private val hub = Actor.actor[(RW => Any) \/ (R => Unit)] {
    case -\/(f) => // request to modify
      val buf = new collection.mutable.ArrayBuffer[R]()
      views = views.filter { ref =>
        val got = ref.get
        if (null == got) false
        else { buf += got; true }
      }
      if (views.nonEmpty) { // still some active aliases, copy
        if (copyViews(readWrite,buf)) buf.foreach(copyView)
        else readWrite = copy(readWrite)
        views = Vector() // either all views have been copied
      }
      f(readWrite) // we now have a unique view, modify in place
    case \/-(get) => // request to get the current value
      val r = read(readWrite)
      views = views :+ new WeakReference(r)
      get(r)
  } (Strategy.Sequential)
}

//trait Buffer[@specialized A] {
//
//  def view: View[A]
//}
//
//trait View[@specialized A] {
//  def valid(ind: Int): Boolean
//  def at(i: Int): Byte
//}
//
//object Bytes {
//  def
//}
