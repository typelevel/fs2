package fs2.util

/** A `Function1` that supports stack-safe function composition via `compose` and `andThen`. */
final class SFunction1[-A, +B] private (private val fs: Catenable[(Any => Any)]) extends (A => B) {

  def apply(a: A): B = fs.foldLeft(a: Any)((x, f) => f(x)).asInstanceOf[B]

  override def compose[C](g: C => A): SFunction1[C, B] =
    new SFunction1(g.asInstanceOf[(Any => Any)] +: fs)

  override def andThen[C](g: B => C): SFunction1[A, C] =
    new SFunction1(fs :+ g.asInstanceOf[(Any => Any)])

  override def toString = "<function1>"
}

object SFunction1 {
  /** Creates a `SFunction1` from the supplied function. */
  def apply[A, B](f: A => B): SFunction1[A, B] = new SFunction1(Catenable.single(f.asInstanceOf[Any => Any]))
}
