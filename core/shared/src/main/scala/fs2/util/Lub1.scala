package fs2
package util

/**
 * A `Lub1[F,G,Lub]` is evidence that `forall x`:
 *  - `Lub[x] >: F[x]`
 *  - `Lub[x] >: G[x]`
 *  - there is no `L[x]` for which `L[x] >: F[x]` and `L[x] >: G[x]` and `L[x] <: Lub[x]`
 */
sealed trait Lub1[-F[_],-G[_],+Lub[_]] {
  implicit def subF: Sub1[F,Lub]
  implicit def subG: Sub1[G,Lub]
}

trait Lub1Instances0 {
  implicit def lub1[F[_],G[_],Lub[_]](implicit S1: Sub1[F,Lub], S2: Sub1[G,Lub]): Lub1[F,G,Lub] = new Lub1[F,G,Lub] {
    def subF = S1
    def subG = S2
  }
}

trait Lub1Instances1 extends Lub1Instances0 {
  implicit def lubOfFAndPureIsF[F[_]]: Lub1[F,Pure,F] = new Lub1[F,Pure,F] {
    def subF = implicitly
    def subG = implicitly
  }
  implicit def lubOfPureAndFIsF[F[_]]: Lub1[Pure,F,F] = new Lub1[Pure,F,F] {
    def subF = implicitly
    def subG = implicitly
  }
}

object Lub1 extends Lub1Instances1 {
 implicit def id[F[_]]: Lub1[F,F,F] = new Lub1[F,F,F] {
    def subF = implicitly
    def subG = implicitly
  }
}

