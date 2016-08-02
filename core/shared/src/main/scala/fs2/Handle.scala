package fs2

import fs2.util.{Async,RealSupertype,Sub1}

/**
 * A currently open `Stream[F,A]`, allowing chunks to be pulled or pushed.
 */
final class Handle[+F[_],+A](
  private[fs2] val buffer: List[Chunk[A]],
  private[fs2] val underlying: Stream[F,A]
) {

  private[fs2] def stream: Stream[F,A] = {
    def go(buffer: List[Chunk[A]]): Stream[F,A] = buffer match {
      case List() => underlying
      case c :: buffer => Stream.chunk(c) ++ go(buffer)
    }
    go(buffer)
  }

  def map[A2](f: A => A2): Handle[F,A2] = new Handle(buffer.map(_ map f), underlying map f)

  def push[A2>:A](c: Chunk[A2])(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
    if (c.isEmpty) this
    else new Handle(c :: buffer, underlying)

  def push1[A2>:A](a: A2)(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
    push(Chunk.singleton(a))

  def await: Pull[F,Nothing,(Chunk[A],Handle[F,A])] =
    buffer match {
      case List() => underlying.step
      case hb :: tb => Pull.pure((hb, new Handle(tb, underlying)))
    }

  def await1: Pull[F,Nothing,(A,Handle[F,A])] =
    await flatMap { case (hd, tl) => hd.uncons match {
      case None => tl.await1
      case Some((h,hs)) => Pull.pure((h, tl push hs))
    }}

  def awaitNonempty: Pull[F, Nothing, (Chunk[A], Handle[F,A])] = Pull.awaitNonempty(this)

  def echo1: Pull[F,A,Handle[F,A]] = Pull.echo1(this)

  def echoChunk: Pull[F,A,Handle[F,A]] = Pull.echoChunk(this)

  def peek: Pull[F, Nothing, (Chunk[A], Handle[F,A])] =
    await flatMap { case (hd, tl) => Pull.pure((hd, tl.push(hd))) }

  def peek1: Pull[F, Nothing, (A, Handle[F,A])] =
    await1 flatMap { case (hd, tl) => Pull.pure((hd, tl.push1(hd))) }

  def awaitAsync[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Async[F2], A2: RealSupertype[A,A2]): Pull[F2, Nothing, Handle.AsyncStep[F2,A2]] = {
    val h = Sub1.substHandle(this)(S)
    h.buffer match {
      case List() => h.underlying.stepAsync
      case hb :: tb => Pull.pure(ScopedFuture.pure(Pull.pure((hb, new Handle(tb, h.underlying)))))
    }
  }

  def await1Async[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Async[F2], A2: RealSupertype[A,A2]): Pull[F2, Nothing, Handle.AsyncStep1[F2,A2]] = {
    awaitAsync map { _ map { _.map {
        case (hd, tl) => hd.uncons match {
          case None => (None, tl)
          case Some((h,hs)) => (Some(h), tl.push(hs))
        }}}
      }
  }

  def covary[F2[_]](implicit S: Sub1[F,F2]): Handle[F2,A] = Sub1.substHandle(this)

  override def toString = s"Handle($buffer, $underlying)"
}

object Handle {
  def empty[F[_],A]: Handle[F,A] = new Handle(List(), Stream.empty)

  implicit class HandleInvariantEffectOps[F[_],+A](private val self: Handle[F,A]) extends AnyVal {

    def receive[O,B](f: (Chunk[A],Handle[F,A]) => Pull[F,O,B]): Pull[F,O,B] = self.await.flatMap(f.tupled)

    def receive1[O,B](f: (A,Handle[F,A]) => Pull[F,O,B]): Pull[F,O,B] = self.await1.flatMap(f.tupled)

    def receiveOption[O,B](f: Option[(Chunk[A],Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      Pull.receiveOption(f)(self)

    def receive1Option[O,B](f: Option[(A,Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      Pull.receive1Option(f)(self)

    def receiveNonemptyOption[O,B](f: Option[(Chunk[A],Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      Pull.receiveNonemptyOption(f)(self)
  }

  type AsyncStep[F[_],A] = ScopedFuture[F, Pull[F, Nothing, (Chunk[A], Handle[F,A])]]
  type AsyncStep1[F[_],A] = ScopedFuture[F, Pull[F, Nothing, (Option[A], Handle[F,A])]]
}
