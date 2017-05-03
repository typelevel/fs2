package fs2
package util

class Lub1Spec extends Fs2Spec {
  trait Foo[A]
  trait Bar[A] extends Foo[A]
  trait Baz[A] extends Foo[A]

  "Lub1" - {

    "computes least upper bound" in {
      implicitly[Lub1[Bar, Baz, Foo]]

      // def implications[F[_],G[_],H[_]](implicit L: Lub1[F,G,H]) = {
      //   implicitly[Lub1[F,H,H]]
      //   implicitly[Lub1[H,F,H]]
      //   implicitly[Lub1[G,H,H]]
      //   implicitly[Lub1[H,G,H]]
      // }
    }
  }
}