package fs2.internal

/** Alternative to `scala.util.control.NonFatal` that only considers `VirtualMachineError`s as fatal. */
object NonFatal {

  def apply(t: Throwable): Boolean = t match {
    case _: VirtualMachineError => false
    case _                      => true
  }

  def unapply(t: Throwable): Option[Throwable] =
    if (apply(t)) Some(t) else None
}
