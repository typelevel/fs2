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

  /** Await a single element from this `Handle`. */
  def await1: Pull[F,Nothing,(A,Handle[F,A])] =
    await flatMap { case (hd, tl) => hd.uncons match {
      case None => tl.await1
      case Some((h,hs)) => Pull.pure((h, tl push hs))
    }}

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

  /** Like `await`, but return a `Chunk` of no more than `maxChunkSize` elements. */
  def awaitLimit(maxChunkSize: Int): Pull[F,Nothing,(Chunk[A],Handle[F,A])] =
    await.map { case s @ (hd, tl) =>
      if (hd.size <= maxChunkSize) s
      else (hd.take(maxChunkSize), tl.push(hd.drop(maxChunkSize)))
    }

  /** Return a `List[Chunk[I]]` from the input whose combined size has a maximum value `n`. */
  def awaitN(n: Int, allowFewer: Boolean = false): Pull[F,Nothing,(List[Chunk[A]],Handle[F,A])] =
    if (n <= 0) Pull.pure((Nil, this))
    else for {
      (hd, tl) <- awaitLimit(n)
      (hd2, tl) <- _awaitN0(n, allowFewer)((hd, tl))
    } yield ((hd :: hd2), tl)

  private def _awaitN0[G[_], X](n: Int, allowFewer: Boolean): ((Chunk[X],Handle[G,X])) => Pull[G, Nothing, (List[Chunk[X]], Handle[G,X])] = {
    case (hd, tl) =>
      val next = tl.awaitN(n - hd.size, allowFewer)
      if (allowFewer) next.optional.map(_.getOrElse((Nil, Handle.empty))) else next
  }

  /** Await the next available non-empty `Chunk`. */
  def awaitNonEmpty: Pull[F,Nothing,(Chunk[A],Handle[F,A])] =
    this.receive { case s @ (hd, tl) => if (hd.isEmpty) tl.awaitNonEmpty else Pull.pure(s) }

  /** Await the next available chunk from the input, or `None` if the input is exhausted. */
  def awaitOption: Pull[F,Nothing,Option[(Chunk[A],Handle[F,A])]] =
    await.map(Some(_)) or Pull.pure(None)

  /** Await the next available element from the input, or `None` if the input is exhausted. */
  def await1Option: Pull[F,Nothing,Option[(A,Handle[F,A])]] =
    await1.map(Some(_)) or Pull.pure(None)

  /** Await the next available non-empty chunk from the input, or `None` if the input is exhausted. */
  def awaitNonEmptyOption: Pull[F, Nothing, Option[(Chunk[A], Handle[F,A])]] =
    awaitNonEmpty.map(Some(_)) or Pull.pure(None)

  /** Copy the next available chunk to the output. */
  def copy: Pull[F,A,Handle[F,A]] =
    this.receive { (chunk, h) => Pull.output(chunk) >> Pull.pure(h) }

  /** Copy the next available element to the output. */
  def copy1: Pull[F,A,Handle[F,A]] =
    this.receive1 { (hd, h) => Pull.output1(hd) >> Pull.pure(h) }

  /** Drop the first `n` elements of this `Handle`, and return the new `Handle`. */
  def drop(n: Long): Pull[F, Nothing, Handle[F, A]] =
    if (n <= 0) Pull.pure(this)
    else awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue).flatMap {
      case (chunk, h) => h.drop(n - chunk.size)
    }

  /**
   * Drop elements of the this `Handle` until the predicate `p` fails, and return the new `Handle`.
   * If non-empty, the first element of the returned `Handle` will fail `p`.
   */
  def dropWhile(p: A => Boolean): Pull[F,Nothing,Handle[F,A]] =
    this.receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h push chunk)
        case Some(i) => Pull.pure(h push chunk.drop(i))
        case None    => h.dropWhile(p)
      }
    }

  /** Write all inputs to the output of the returned `Pull`. */
  def echo: Pull[F,A,Nothing] =
    echoChunk.flatMap(_.echo)

  /** Read a single element from the input and emit it to the output. Returns the new `Handle`. */
  def echo1: Pull[F,A,Handle[F,A]] =
    this.receive1 { (a, h) => Pull.output1(a) >> Pull.pure(h) }

  /** Read the next available chunk from the input and emit it to the output. Returns the new `Handle`. */
  def echoChunk: Pull[F,A,Handle[F,A]] =
    this.receive { (c, h) => Pull.output(c) >> Pull.pure(h) }

  /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  def fetchN(n: Int): Pull[F,Nothing,Handle[F,A]] =
    awaitN(n) map { case (buf, h) => buf.reverse.foldLeft(h)(_ push _) }

  /** Await the next available element where the predicate returns true */
  def find(f: A => Boolean): Pull[F,Nothing,(A,Handle[F,A])] =
    this.receive { (chunk, h) =>
      chunk.indexWhere(f) match {
        case None => h.find(f)
        case Some(a) if a + 1 < chunk.size => Pull.pure((chunk(a), h.push(chunk.drop(a + 1))))
        case Some(a) => Pull.pure((chunk(a), h))
      }
    }

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
   * result to the output of the supplied `Pull` when the stream has no more values.
   */
  def fold[B](z: B)(f: (B, A) => B): Pull[F,Nothing,B] =
    await.optional flatMap {
      case Some((c, h)) => h.fold(c.foldLeft(z)(f))(f)
      case None => Pull.pure(z)
    }

  /**
   * Folds all inputs using the supplied binary operator, and writes the final result to the output of
   * the supplied `Pull` when the stream has no more values.
   */
  def fold1[A2 >: A](f: (A2, A2) => A2): Pull[F,Nothing,A2] =
    this.receive1 { (o, h) => h.fold[A2](o)(f) }

  /** Write a single `true` value if all input matches the predicate, false otherwise */
  def forall(p: A => Boolean): Pull[F,Nothing,Boolean] = {
    await1.optional flatMap {
      case Some((a, h)) =>
        if (!p(a)) Pull.pure(false)
        else h.forall(p)
      case None => Pull.pure(true)
    }
  }

  /** Return the last element of the input, if non-empty. */
  def last: Pull[F,Nothing,Option[A]] = {
    def go(prev: Option[A]): Handle[F,A] => Pull[F,Nothing,Option[A]] =
      h => h.await.optional.flatMap {
        case None => Pull.pure(prev)
        case Some((c, h)) => go(c.foldLeft(prev)((_,a) => Some(a)))(h)
      }
    go(None)(this)
  }

  def peek: Pull[F, Nothing, (Chunk[A], Handle[F,A])] =
    await flatMap { case (hd, tl) => Pull.pure((hd, tl.push(hd))) }

  def peek1: Pull[F, Nothing, (A, Handle[F,A])] =
    await1 flatMap { case (hd, tl) => Pull.pure((hd, tl.push1(hd))) }

  /**
   * Like `[[await]]`, but runs the `await` asynchronously. A `flatMap` into
   * inner `Pull` logically blocks until this await completes.
   */
  def prefetch[F2[_]](implicit sub: Sub1[F,F2], F: Async[F2]): Pull[F2,Nothing,Pull[F2,Nothing,Handle[F2,A]]] =
    awaitAsync map { fut =>
      fut.pull flatMap { p =>
        p map { case (hd, h) => h push hd }
      }
    }

  /** Emit the first `n` elements of the input and return the new `Handle`. */
  def take(n: Long): Pull[F,A,Handle[F,A]] =
    if (n <= 0) Pull.pure(this)
    else awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue).flatMap {
      case (chunk, h) => Pull.output(chunk) >> h.take(n - chunk.size.toLong)
    }

  /** Emits the last `n` elements of the input. */
  def takeRight(n: Long): Pull[F,Nothing,Vector[A]]  = {
    def go(acc: Vector[A])(h: Handle[F,A]): Pull[F,Nothing,Vector[A]] = {
      h.awaitN(if (n <= Int.MaxValue) n.toInt else Int.MaxValue, true).optional.flatMap {
        case None => Pull.pure(acc)
        case Some((cs, h)) =>
          val vector = cs.toVector.flatMap(_.toVector)
          go(acc.drop(vector.length) ++ vector)(h)
      }
    }
    if (n <= 0) Pull.pure(Vector())
    else go(Vector())(this)
  }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough(p: A => Boolean): Pull[F,A,Handle[F,A]] =
    this.receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(a) => Pull.output(chunk.take(a+1)) >> Pull.pure(h.push(chunk.drop(a+1)))
        case None => Pull.output(chunk) >> h.takeThrough(p)
      }
    }

  /**
   * Emit the elements of this `Handle` until the predicate `p` fails,
   * and return the new `Handle`. If non-empty, the returned `Handle` will have
   * a first element `i` for which `p(i)` is `false`. */
  def takeWhile(p: A => Boolean): Pull[F,A,Handle[F,A]] =
    this.receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h.push(chunk))
        case Some(a) => Pull.output(chunk.take(a)) >> Pull.pure(h.push(chunk.drop(a)))
        case None    => Pull.output(chunk) >> h.takeWhile(p)
      }
    }

  implicit def covary[F2[_]](implicit S: Sub1[F,F2]): Handle[F2,A] = Sub1.substHandle(this)

  override def toString = s"Handle($buffer, $underlying)"
}

object Handle {
  def empty[F[_],A]: Handle[F,A] = new Handle(List(), Stream.empty)

  implicit class HandleInvariantEffectOps[F[_],+A](private val self: Handle[F,A]) extends AnyVal {

    /** Apply `f` to the next available `Chunk`. */
    def receive[O,B](f: (Chunk[A],Handle[F,A]) => Pull[F,O,B]): Pull[F,O,B] = self.await.flatMap(f.tupled)

    /** Apply `f` to the next available element. */
    def receive1[O,B](f: (A,Handle[F,A]) => Pull[F,O,B]): Pull[F,O,B] = self.await1.flatMap(f.tupled)

    /** Apply `f` to the next available chunk, or `None` if the input is exhausted. */
    def receiveOption[O,B](f: Option[(Chunk[A],Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      self.awaitOption.flatMap(f)

    /** Apply `f` to the next available element, or `None` if the input is exhausted. */
    def receive1Option[O,B](f: Option[(A,Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      self.await1Option.flatMap(f)

    def receiveNonEmptyOption[O,B](f: Option[(Chunk[A],Handle[F,A])] => Pull[F,O,B]): Pull[F,O,B] =
      self.awaitNonEmptyOption.flatMap(f)
  }

  type AsyncStep[F[_],A] = ScopedFuture[F, Pull[F, Nothing, (Chunk[A], Handle[F,A])]]
  type AsyncStep1[F[_],A] = ScopedFuture[F, Pull[F, Nothing, (Option[A], Handle[F,A])]]
}
