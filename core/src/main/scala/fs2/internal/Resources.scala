package fs2.internal

import Resources._

/**
 * Some implementation notes:
 *
 * `Some(r)` in the `LinkedMap` represents an acquired resource;
 * `None` represents a resource in the process of being acquired
 * The `Status` indicates whether this resource is 'open' or not.
 * Once `Closed` or `Closing`, all `startAcquire` calls will return `false`.
 * When `Closing`, all calls to `finishAcquire` or `cancelAcquire` will
 * transition to `Closed` if there are no more outstanding acquisitions.
 *
 * Once `Closed` or `Closing`, there is no way to reopen a `Resources`.
 */
private[fs2]
class Resources[T,R](tokens: Ref[(Status, LinkedMap[T, Either[List[() => Unit], R]])], val name: String = "Resources") {

  def isOpen: Boolean = tokens.get._1 == Open
  def isClosed: Boolean = tokens.get._1 == Closed
  def isClosing: Boolean = { val t = tokens.get._1; t == Closing || t == Closed }
  def isEmpty: Boolean = tokens.get._2.isEmpty
  def size: Int = tokens.get._2.size
  /** Take a snapshot of current tokens. */
  def snapshot: Set[T] = tokens.get._2.keys.toSet
  /** Return the list of tokens allocated since the given snapshot, newest first. */
  def newSince(snapshot: Set[T]): List[T] =
    tokens.get._2.keys.toList.filter(k => !snapshot(k))
  def release(ts: List[T]): Option[(List[R],List[T])] = tokens.access match {
    case ((open,m), update) =>
      if (ts.forall(t => m.get(t).forall(_.fold(_ => false, _ => true)))) {
        val rs = ts.flatMap(t => m.get(t).toList.collect { case Right(r) => r })
        val m2 = m.removeKeys(ts)
        if (!update(open -> m2)) release(ts) else Some(rs -> ts.filter(t => m.get(t).isEmpty))
      }
      else None
  }

  /**
   * Close this `Resources` and return all acquired resources.
   * After finishing, no calls to `startAcquire` will succeed.
   * Returns `Left(n)` if there are n > 0 resources in the process of being
   * acquired, and registers a thunk to be invoked when the resource
   * is done being acquired.
   */
  @annotation.tailrec
  final def closeAll(waiting: => Stream[() => Unit]): Either[Int, List[(T,R)]] = tokens.access match {
    case ((open,m),update) =>
      val totallyDone = m.values.forall(_.isRight)
      def rs = m.orderedEntries.collect { case (t,Right(r)) => (t,r) }.toList
      lazy val m2 =
        if (!totallyDone) {
          val ws = waiting
          val beingAcquired = m.orderedEntries.iterator.collect { case (_, Left(_)) => 1 }.sum
          if (ws.lengthCompare(beingAcquired) < 0) throw new IllegalArgumentException("closeAll - waiting too short")
          LinkedMap(m.orderedEntries.zip(ws).collect { case ((t, Left(ws)), w) => (t, Left(w::ws)) })
        }
        else LinkedMap.empty[T,Either[List[() => Unit],R]]
      if (update((if (totallyDone) Closed else Closing, m2))) {
        if (totallyDone) Right(rs)
        else Left(m2.size)
      }
      else closeAll(waiting)
  }

  /**
   * Close `t`, returning any associated acquired resource.
   * Returns `None` if `t` is being acquired or `t` is
   * not present in this `Resources`.
   */
  @annotation.tailrec
  final def startClose(t: T): Option[R] = tokens.access match {
    case ((Open,m),update) => m.get(t) match {
      case None => None // note: not flatMap so can be tailrec
      case Some(Right(r)) => // close of an acquired resource
        if (update((Open, m.updated(t, Left(List()))))) Some(r)
        else startClose(t)
      case Some(Left(_)) => None // close of any acquiring resource fails
    }
    case _ => None // if already closed or closing
  }

  final def finishClose(t: T): Unit = tokens.modify {
    case (open,m) => m.get(t) match {
      case Some(Left(_)) => (open, m-t)
      case _ => sys.error("close of unknown resource: "+t)
    }
  }

  /**
   * Start acquiring `t`.
   */
  @annotation.tailrec
  final def startAcquire(t: T): Boolean = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(r) => sys.error("startAcquire on already used token: "+(t -> r))
        case None => open == Open && {
          update(open -> m.edit(t, _ => Some(Left(List())))) || startAcquire(t)
        }
      }
  }

  /**
   * Cancel acquisition of `t`.
   */
  @annotation.tailrec
  final def cancelAcquire(t: T): Unit = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(Right(r)) => () // sys.error("token already acquired: "+ (t -> r))
        case None => ()
        case Some(Left(cbs)) =>
          val m2 = m - t
          val totallyDone = m2.values.forall(_ != None)
          val status = if (totallyDone && open == Closing) Closed else open
          if (update(status -> m2)) cbs.foreach(thunk => thunk())
          else cancelAcquire(t)
      }
  }

  /**
   * Associate `r` with the given `t`.
   */
  @annotation.tailrec
  final def finishAcquire(t: T, r: R): Unit = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(Left(waiting)) =>
          val m2 = m.edit(t, _ => Some(Right(r)))
          if (update(open -> m2))
            waiting.foreach(thunk => thunk())
          else
            finishAcquire(t,r) // retry on contention
        case r => sys.error("expected acquiring status, got: " + r)
      }
  }

  override def toString = tokens.toString
}

private[fs2] object Resources {

  def empty[T,R]: Resources[T,R] =
    new Resources[T,R](Ref(Open -> LinkedMap.empty))

  def emptyNamed[T,R](name: String): Resources[T,R] =
    new Resources[T,R](Ref(Open -> LinkedMap.empty), name)

  trait Status
  case object Closed extends Status
  case object Closing extends Status
  case object Open extends Status
}
