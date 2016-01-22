package fs2.internal

import Resources.{Status,Trie}
import Status._

private[fs2] class Resources[T,R](tokens: Ref[Trie[T, Status[R]]]) {

  def isClosed(path: Seq[T]): Boolean = tokens.get.get(path).isEmpty

  @annotation.tailrec
  final def open(path: Seq[T]): Boolean = tokens.access match {
    case (m,update) => m.get(path) match {
      case None =>
        update(m.edit(path, Open, _ => Open)) || open(path)
      case Some(t) =>
        sys.error("open of already open path: " + path)
    }
  }

  @annotation.tailrec
  final def close(path: Seq[T]): Option[Vector[R]] = tokens.access match {
    case (m,update) => m.subtree(path) match {
      case None => Some(Vector.empty)
      case Some(t) =>
        def trimmed = t.values.collect { case Status.Acquired(v) => v }.toVector
        def hasAcquires = t.values.collect { case Status.Acquiring => () }.nonEmpty
        // fail permanently if any resources are being currently acquired
        if (hasAcquires) None
        else if (update(m.trim(path))) Some(trimmed)
        // retry if failure due to contention
        else close(path)
    }
  }

  @annotation.tailrec
  final def startAcquire(path: Seq[T]): Boolean = tokens.access match { case (m,update) =>
    m.get(path) match {
      case None => false // fail permanently if already closed
      case Some(_) => update(m.edit(path, Open, _ => Acquiring)) ||
                      startAcquire(path) // retry on contention
    }
  }

  @annotation.tailrec
  final def finishAcquire(path: Vector[T], r: R): Boolean = tokens.access match { case (m,update) =>
    m.get(path) match {
      case None => sys.error("finish acquire called on closed path: " + path)
      case Some(sub) => update {
        m.edit(path, Status.Open, _ => Status.Acquired(r))
      } || finishAcquire(path, r) // retry on contention
    }
  }
}

private[fs2] object Resources {

  trait Status[+V]
  object Status {
    /** Indicates a resource is in the process of being acquired for this path. */
    case object Acquiring extends Status[Nothing]
    /** Indicates the path is open, but no resources are associated with it. */
    case object Open extends Status[Nothing]
    /** The path is open, and there is an associated cleanup action. */
    case class Acquired[V](cleanup: V) extends Status[V]
  }

  case class Trie[K,V](value: V, children: LinkedMap[K,Trie[K,V]]) {
    def subtree(s: Seq[K]): Option[Trie[K,V]] =
      s.foldLeft(Some(this): Option[Trie[K,V]]) { (cur,k) =>
        cur.flatMap { cur => cur.children.get(k) }
      }
    /** Cut off subtrees that are prefixed by `s`, including `s`. */
    def trim(s: Seq[K]): Trie[K,V] = s match {
      case Seq() => this
      case Seq(hd, tl@_*) =>
        Trie(value, children.edit(hd, sub => sub.map(_.trim(tl))))
    }

    /**
     * Insert `s` into this `Trie`. If internal nodes must be created,
     * to create a leaf node with a path of `s`, they are given the value
     * `spine`. The leaf node created by this edit will have a value `leaf(spine)`.
     */
    def edit(s: Seq[K], spine: V, leaf: V => V): Trie[K,V] = s match {
      case Seq() => Trie(leaf(value), LinkedMap.empty)
      case Seq(hd, tl@_*) =>
        val children2 = children.edit(
          hd,
          sub => Some { sub.getOrElse(Trie(spine, LinkedMap.empty[K,Trie[K,V]]))
                           .edit(tl, spine, leaf) }
        )
        Trie(value, children2)
    }

    def get(s: Seq[K]): Option[V] = subtree(s).map(_.value)
    def along(s: Seq[K]): List[V] = s match {
      case Seq() => List(value)
      case Seq(hd, tl@_*) => children.get(hd).map(t => value :: t.along(tl)).getOrElse(List())
    }
    def values: Stream[V] = children.values.toStream.flatMap(_.values) ++ Stream(value)
  }
}
