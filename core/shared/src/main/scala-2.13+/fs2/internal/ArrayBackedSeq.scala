package fs2.internal

private[fs2] object ArrayBackedSeq {
  def unapply[A](a: collection.Seq[A]): Option[Array[_]] = a match {
    case as: collection.immutable.ArraySeq[A] => Some(as.unsafeArray)
    case as: collection.mutable.ArraySeq[A] => Some(as.array)
    case _ => None
  }
}