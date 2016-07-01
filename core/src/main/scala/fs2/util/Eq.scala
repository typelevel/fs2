package fs2
package util

/** Evidence that `A` is the same type as `B` (a better version of `=:=`). */
private[fs2] sealed trait Eq[A,B] {
  def apply(a: A): B = Eq.subst[({type f[x] = x})#f, A, B](a)(this)
  def flip: Eq[B,A] = this.asInstanceOf[Eq[B,A]]
}

private[fs2] object Eq {
  private val _instance = new Eq[Unit,Unit] {}
  implicit def refl[A]: Eq[A,A] = _instance.asInstanceOf[Eq[A,A]]

  def subst[F[_],A,B](f: F[A])(implicit Eq: Eq[A,B]): F[B] =
    f.asInstanceOf[F[B]]
}
