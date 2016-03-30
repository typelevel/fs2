package object fs2 {

  private[fs2] def trace(msg: => String): Unit = ()
  private[fs2] class NamedFunction1[-A, +B](f: A => B, name: String) extends (A => B) {
    def apply(a: A) = f(a)
    override def toString = s"<$name>"
  }

  type Process1[-I,+O] = process1.Process1[I,O]
  type Tee[-I,-I2,+O] = tee.Tee[I,I2,O]
  type Wye[F[_],-I,-I2,+O] = wye.Wye[F,I,I2,O]
  type Channel[F[_],-I,+O] = Stream[F,I] => Stream[F,O]
  type Sink[F[_],-I] = Channel[F,I,Unit]

  @deprecated("renamed to fs2.Stream", "0.9")
  type Process[+F[_],+O] = Stream[F,O]
  @deprecated("renamed to fs2.Stream", "0.9")
  val Process = Stream
}
