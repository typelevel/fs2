package fs2

package object util {
  type NotNothing[F[_]] = Sub1[F,F]
}
