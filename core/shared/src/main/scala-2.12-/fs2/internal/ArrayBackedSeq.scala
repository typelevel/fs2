package fs2.internal

private[fs2] object ArrayBackedSeq {
  def unapply[A](a: collection.Seq[A]): Option[Array[_]] = a match {
    case as: collection.mutable.WrappedArray[A] => Some(as.array)
    case as: collection.mutable.ArraySeq[A] => Some(as.array)
    case _ => None
  }
}