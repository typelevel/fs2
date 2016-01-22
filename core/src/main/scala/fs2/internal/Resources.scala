package fs2.internal

import Resources.Status
import Status._

private[fs2] class Resources[T,R](tokens: Ref[(Boolean, LinkedMap[T, Status[R]])]) {

  def isClosed(t: T): Boolean = tokens.get._2.get(t).isEmpty

  /**
   * Mark the token `t` as being in the `Open` state.
   * This must be done before any calls to `startAcquire`
   * for `t`.
   */
  @annotation.tailrec
  final def open(t: T): Unit = tokens.access match {
    case ((isOpen,m),update) => m.get(t) match {
      case None =>
        if (!update((isOpen,m.edit(t, _ => Some(Open))))) open(t)
      case Some(t) =>
        sys.error("open of already open token: " + t)
    }
  }

  /**
   * Close this `Resources` and return all acquired resources.
   * The `Boolean` is `false` if there are any outstanding
   * resources in the `Acquiring` state. After finishing,
   * no calls to `startAcquire` will succeed.
   */
  @annotation.tailrec
  final def closeAll: (List[R], Boolean) = tokens.access match {
    case ((open,m),update) =>
      val rs = m.values.collect { case Acquired(r) => r }.toList
      val anyAcquiring = m.values.exists(_ == Acquiring)
      val m2 = m.unorderedEntries.foldLeft(m) { (m,kv) =>
        kv._2 match {
          case Acquiring => m
          case _ => m - kv._1
        }
      }
      if (!update((false, m2))) closeAll
      else (rs, !anyAcquiring)
  }

  @annotation.tailrec
  final def close(t: T): Option[R] = tokens.access match {
    case ((open,m),update) => m.get(t) match {
      case None => None // pattern matching so this can be tailrec
      case Some(Acquired(r)) =>
        if (update((open, m-t))) Some(r)
        else close(t)
      case Some(Open) =>
        if (update((open,m-t))) None
        else close(t)
      case Some(Acquiring) => None
    }
  }

  @annotation.tailrec
  final def startAcquire(t: T): Boolean = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(_) if open =>
          update(open -> m.edit(t, _ => Some(Acquiring))) ||
          startAcquire(t) // retry on contention
        case _ => false // fail permanently if already closed
      }
  }

  @annotation.tailrec
  final def finishAcquire(t: T, r: R): Unit = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(Acquiring) =>
          if (!update(open -> m.edit(t, _ => Some(Acquired(r)))))
            finishAcquire(t,r) // retry on contention
        case r => sys.error("expected Acquiring status, got: " + r)
      }
  }
}

private[fs2] object Resources {

  def empty[T,R]: Resources[T,R] =
    new Resources[T,R](Ref(true -> LinkedMap.empty))

  sealed trait Status[+V]
  object Status {
    /** Indicates a resource is in the process of being acquired for this path. */
    case object Acquiring extends Status[Nothing]
    /** Indicates the path is open, but no resources are associated with it. */
    case object Open extends Status[Nothing]
    /** The path is open, and there is an associated cleanup action. */
    case class Acquired[V](cleanup: V) extends Status[V]
  }
}
