package fs2

package object util {

  type ~>[F[_],G[_]] = UF1[F,G]

  type Attempt[A] = Either[Throwable,A]
}
