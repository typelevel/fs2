package fs2

package object internal {
  private[fs2] type Factory[-A, +C] = scala.collection.Factory[A, C]
}
