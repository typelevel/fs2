package fs2

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

package object internal {
  private[fs2] type Factory[-A, +C] = CanBuildFrom[Nothing, A, C]

  private[fs2] implicit class FactoryOps[-A, +C](private val factory: Factory[A, C]) {
    def newBuilder: Builder[A, C] = factory()
  }
}
