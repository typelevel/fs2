package fs2

package object util {

  type ~>[F[_], G[_]] = UF1[F, G]

  type NotNothing[F[_]] = Sub1[F,F]

  def notNothing[F[_]]: NotNothing[F] = Sub1.sub1[F]
}
