package fs2.internal

/**
 * Some implementation notes:
 *
 * `Some(r)` in the `LinkedMap` represents an acquired resource;
 * `None` represents a resource in the process of being acquired
 * The `Boolean` indicates whether this resource is 'open' or not.
 * Once closed, all `startAcquire` calls will return `false`.
 * Once closed, there is no way to reopen a `Resources`.
 */
private[fs2] class Resources[T,R](tokens: Ref[(Boolean, LinkedMap[T, Option[R]])]) {

  def isOpen: Boolean = tokens.get._1
  def isClosed: Boolean = !isOpen
  def isEmpty: Boolean = tokens.get._2.isEmpty

  /**
   * Close this `Resources` and return all acquired resources.
   * The `Boolean` is `false` if there are any outstanding
   * resources in the `Acquiring` state. After finishing,
   * no calls to `startAcquire` will succeed.
   */
  @annotation.tailrec
  final def closeAll: (List[R], Boolean) = tokens.access match {
    case ((open,m),update) =>
      val rs = m.values.collect { case Some(r) => r }.toList
      val anyAcquiring = m.values.exists(_ == None)
      val m2 = m.unorderedEntries.foldLeft(m) { (m,kv) =>
        kv._2 match {
          case None => m
          case Some(_) => m - kv._1
        }
      }
      if (!update((false, m2))) closeAll
      else (rs, !anyAcquiring)
  }

  /**
   * Close `t`, returning any associated acquired resource.
   * Returns `None` if `t` is being acquired or `t` is
   * not present in this `Resources`.
   */
  @annotation.tailrec
  final def close(t: T): Option[R] = tokens.access match {
    case ((open,m),update) => m.get(t) match {
      case None => None // note: not flatMap so can be tailrec
      case Some(Some(r)) => // close of an acquired resource
        if (update((open, m-t))) Some(r)
        else close(t)
      case Some(None) => None // close of any acquiring resource fails
    }
  }

  /**
   * Start acquiring `t`.
   * Returns `None` if `t` is being acquired or `t` is
   * not present in this `Resources`.
   */
  @annotation.tailrec
  final def startAcquire(t: T): Boolean = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(r) => sys.error("startAcquire on already used token: "+(t -> r))
        case None => open && {
          update(open -> m.edit(t, _ => Some(None))) || startAcquire(t)
        }
      }
  }

  /**
   * Associate `r` with the given `t`.
   * Returns `open` status of this `Resources` as of the update.
   */
  @annotation.tailrec
  final def finishAcquire(t: T, r: R): Boolean = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(None) =>
          if (!update(open -> m.edit(t, _ => Some(Some(r)))))
            finishAcquire(t,r) // retry on contention
          else open
        case r => sys.error("expected acquiring status, got: " + r)
      }
  }
}

private[fs2] object Resources {

  def empty[T,R]: Resources[T,R] =
    new Resources[T,R](Ref(true -> LinkedMap.empty))
}
