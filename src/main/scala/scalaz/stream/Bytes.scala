package scalaz.stream

/** 
 * An immutable view into an `Array[Byte]`. Indexing past `size - 1`
 * may return garbage rather than throwing an exception. 
 */
class Bytes(private[stream] val bytes: Array[Byte], private[stream] val n: Int) {
  def apply(i: Int) = bytes(i)
  def size: Int = n 
  def toArray: Array[Byte] = {
    val r = new Array[Byte](bytes.length) 
    bytes.copyToArray(r)
    r
  }
}

object Bytes {
  val empty = Bytes(Array[Byte]())
  def apply(bytes:Array[Byte]):Bytes = new Bytes(bytes,bytes.length)
}
