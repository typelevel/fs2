package fs2

/** Represents a unique identifier (using object equality). */
private[fs2] final class Token extends Serializable {
  override def toString: String = s"Token(${hashCode.toHexString})"
}
