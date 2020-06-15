package fs2

package object internal {
  private[fs2] val Algebra = FreeC
  private[fs2] type Factory[-A, +C] = scala.collection.Factory[A, C]
}
