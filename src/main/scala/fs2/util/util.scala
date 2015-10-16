package fs2

package object util {
  type NotNothing[F[_]] = Sub1[F,F]

  def notNothing[F[_]]: NotNothing[F] = Sub1.sub1[F]
}
