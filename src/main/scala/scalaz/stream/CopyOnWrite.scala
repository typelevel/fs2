package scalaz.stream

import java.nio.ByteBuffer
import scala.ref.WeakReference
import scalaz.\/.{left, right}
import scalaz.concurrent.{Actor,Strategy,Task}
import scalaz._

/**
 * Control structure for ensuring safety of in-place updates to some
 * underlying mutable data structure of type `RW` (mnemonic 'read/write').
 * `R` is the type of read-only views into this mutable structure.
 *
 */
trait CopyOnWrite[RW, R] {

  /**
   * Returns an immutable view into this structure, guaranteed
   * to never change even in presence of concurrent updates
   * to the underlying.
   */
  def view: Task[R]

  /**
   * Releases the resource `R`. That means, reads from this resource are
   * unreliable from this point. This is to signal to CoW that `R` is free earlier than
   * Java GC decides that `R` is not used anymore.
   * @param r
   * @return
   */
  def release(r:R): Task[Unit]

  /**
   * Mutates the underlying using the given function, taking care
   * to copy either the views or the underlying.
   */
  def modify(f: RW => Any): Task[Unit]



}


object CopyOnWrite {

  /**
   * Creates CypyOnWrite for ensuring safety of in-place updates to some
   * underlying mutable data structure of type `RW` (mnemonic 'read/write').
   * `R` is the type of read-only views into this mutable structure.
   *
   * @param copy should make a clone of `RW`
   * @param copyView should update `R` _in place_ with an identical copy of itself.
   * @param copyViews should return `true` if there are views active, and we shall make a copy of the views,
   *                  or `false` if we wish to make a copy of the `RW`.
   * @param read      should return `R` from ReadWrite
   */
  def apply[RW, R <: AnyRef](
    rw: RW
    , copy: RW => RW
    , copyView: R => Unit
    , read: RW => R
    , copyViews: (RW, Seq[R]) => Boolean): CopyOnWrite[RW, R] = new CopyOnWrite[RW, R] {

    var readWrite = rw

    var views = Vector[WeakReference[R]]()

    val hub = Actor.actor[Either3[(RW => Any), R => Unit, (R,() => Unit)]] {
      case Left3(f)   => // request to modify
        val buf = new collection.mutable.ArrayBuffer[R]()
        views = views.filter(_.get.isDefined)
        if (views.nonEmpty) {
          // still some active aliases, copy
          if (copyViews(readWrite, buf)) buf.foreach(copyView)
          else readWrite = copy(readWrite)
          views = Vector() // either all views have been copied
        }
        f(readWrite) // we now have a unique view, modify in place
      case Middle3(get) => // request to get the current value
        val r = read(readWrite)
        views = views :+ WeakReference(r)
        get(r)
      case Right3((r,f)) =>
        views = views.filterNot(_.get.exists(_ == r))
        f()
    }(Strategy.Sequential)


    def view: Task[R] =
      Task.async[R](cb => hub ! Middle3((r: R) => cb(right(r))))

    def modify(f: RW => Any): Task[Unit] =
      Task.async[Unit](cb => hub ! Left3((rw: RW) => cb(right(f(rw)))))

    def release(r:R):Task[Unit] =
      Task.async[Unit](cb => hub ! Right3((r, () => cb(right(())))))


  }


  /**
   * Creates Bytes CoW of supplied size
   * @param size size of underlying array of bytes
   * @return
   */
  def bytes(size: Int): CopyOnWrite[Array[Byte], Bytes] = {
    val buf = Array.ofDim[Byte](size)
    // always just copy `buf` if there are live consumers of old value
    CopyOnWrite[Array[Byte], Bytes](
      rw = buf
      , copy =
        buf => {
          val buf2 = Array.ofDim[Byte](size)
          Array.copy(buf, 0, buf2, 0, size)
          buf2
        }
      , copyView = _ => ()
      , read = buf => Bytes.unsafe(buf)
      , copyViews = (_, _) => false)
  }

}


