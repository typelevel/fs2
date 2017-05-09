package fs2.fast

// import fs2.{Chunk,NonEmptyChunk}

// import scala.concurrent.ExecutionContext
// import cats.effect.Effect

import fs2.Chunk

/**
 * A currently open `Stream[F,O]` which allows chunks to be pulled or pushed.
 *
 * To get a handle from a stream, use [[Stream#open]] and subsequently `flatMap` or use [[Stream#pull]].
 */
final class Handle[+F[_],+O] private[fs2] (
  private val buffer: List[Segment[O,Unit]],
  private val underlying: Stream[F,O]
) {

  private[fs2] def stream: Stream[F,O] = {
    def go(buffer: List[Segment[O,Unit]]): Stream[F,O] = buffer match {
      case Nil => underlying
      case s :: buffer => Stream.segment(s) ++ go(buffer)
    }
    go(buffer)
  }

  /** Applies `f` to each element from the source stream, yielding a new handle with a potentially different element type.*/
  def map[O2](f: O => O2): Handle[F,O2] = new Handle(buffer.map(_ map f), underlying map f)

  /** Returns a new handle with the specified segment prepended to elements from the source stream. */
  def push[O2>:O](s: Segment[O2,Unit]): Handle[F,O2] =
    new Handle(s :: buffer, underlying)

  /** Returns a new handle with the specified chunk prepended to elements from the source stream. */
  def pushChunk[O2>:O](c: Chunk[O2]): Handle[F,O2] =
    if (c.isEmpty) this else push(Segment.chunk(c))

  /** Like [[push]] but for a single element instead of a chunk. */
  def push1[O2>:O](o: O2): Handle[F,O2] =
    push(Segment.singleton(o))

  /**
   * Waits for a segment of elements to be available in the source stream.
   * The segment of elements along with a new handle are provided as the resource of the returned pull.
   * The new handle can be used for subsequent operations, like awaiting again.
   * A `None` is returned as the resource of the pull upon reaching the end of the stream.
   */
  def await: Pull[F,Nothing,Option[(Segment[O,Unit],Handle[F,O])]] =
    buffer match {
      case hb :: tb => Pull.pure(Some((hb, new Handle(tb, underlying))))
      case Nil => underlying.uncons.map(_.map { case (segment, stream) => (segment, new Handle(Nil, stream)) })
    }

  /** Like [[await]] but waits for a single element instead of an entire chunk. */
  def await1: Pull[F,Nothing,Option[(O,Handle[F,O])]] =
    await flatMapOpt {
      case (hd, tl) =>
        val (h, rem) = hd.splitAt(1)
        if (h.isEmpty) tl.await1
        else Pull.pure(Some((h(0), rem.fold(_ => tl, tl.push(_)))))
    }

  /**
   * Asynchronously awaits for a segment of elements to be available in the source stream.
   */
  def awaitAsync: Pull[F,O,Pull[F,Nothing,Option[(Segment[O,Unit],Handle[F,O])]]] =
    buffer match {
      case hd :: tl => Pull.pure(Pull.pure(Some((hd, new Handle(tl, underlying)))))
      case Nil => underlying.unconsAsync.map(_.map(_.map { case (segment, stream) => (segment, new Handle(Nil, stream))}))
    }

  // // /** Like [[awaitAsync]] but waits for a single element instead of an entire chunk. */
  // // def await1Async[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Effect[F2], A2: RealSupertype[A,A2], ec: ExecutionContext): Pull[F2, Nothing, Handle.AsyncStep1[F2,A2]] = {
  // //   awaitAsync map { _ map { _.map { case (hd, tl) =>
  // //     val (h, hs) = hd.unconsNonEmpty
  // //     (h, tl.push(hs))
  // //   }}}
  // // }

  /**
   * Like [[await]], but returns a segment of no more than `n` elements.
   *
   * The returned segment has a result tuple consisting of the remaining limit
   * (`n` minus the segment size, or 0, whichever is larger) and a handle for the remainder
   * of the source stream.
   *
   * `Pull.pure(None)` is returned if the end of the source stream is reached.
   */
  def awaitLimit(n: Long): Pull[F,Nothing,Option[Segment[O,(Long,Handle[F,O])]]] = {
    require(n > 0)
    await.map { _.map { case (hd, tl) =>
      hd.take(n).mapResult {
        case None =>
          (0, tl.push(hd.drop(n).voidResult))
        case Some((rem, _)) =>
          (rem, tl)
      }
    }}
  }

  // /** Returns a `List[NonEmptyChunk[A]]` from the input whose combined size has a maximum value `n`. */
  // def awaitN(n: Int, allowFewer: Boolean = false): Pull[F,Nothing,(List[NonEmptyChunk[A]],Handle[F,A])] =
  //   if (n <= 0) Pull.pure((Nil, this))
  //   else for {
  //     (hd, tl) <- awaitLimit(n)
  //     (hd2, tl) <- _awaitN0(n, allowFewer)((hd, tl))
  //   } yield ((hd :: hd2), tl)
  //
  // private def _awaitN0[G[_], X](n: Int, allowFewer: Boolean): ((NonEmptyChunk[X],Handle[G,X])) => Pull[G, Nothing, (List[NonEmptyChunk[X]], Handle[G,X])] = {
  //   case (hd, tl) =>
  //     val next = tl.awaitN(n - hd.size, allowFewer)
  //     if (allowFewer) next.optional.map(_.getOrElse((Nil, Handle.empty))) else next
  // }
  //
  // /** Awaits the next available chunk from the input, or `None` if the input is exhausted. */
  // def awaitOption: Pull[F,Nothing,Option[(NonEmptyChunk[A],Handle[F,A])]] =
  //   await.map(Some(_)) or Pull.pure(None)
  //
  // /** Awaits the next available element from the input, or `None` if the input is exhausted. */
  // def await1Option: Pull[F,Nothing,Option[(A,Handle[F,A])]] =
  //   await1.map(Some(_)) or Pull.pure(None)

  /** Copies the next available chunk to the output. */
  def copy: Pull[F,O,Option[Handle[F,O]]] =
    this.receive { (s, h) => Pull.output(s) >> Pull.pure(h) }

  /** Copies the next available element to the output. */
  def copy1: Pull[F,O,Option[Handle[F,O]]] =
    this.receive1 { (hd, h) => Pull.output1(hd) >> Pull.pure(h) }

  /** Drops the first `n` elements of this `Handle`, and returns the new `Handle`. */
  def drop(n: Long): Pull[F,Nothing,Option[Handle[F,O]]] =
    if (n <= 0) Pull.pure(Some(this))
    else awaitLimit(n).flatMapOpt { s =>
      Pull.segment(s.drain).flatMap { case (rem, tl) =>
        if (rem > 0) tl.drop(rem) else Pull.pure(Some(tl))
      }
    }

  // /**
  //  * Drops elements of the this `Handle` until the predicate `p` fails, and returns the new `Handle`.
  //  * If non-empty, the first element of the returned `Handle` will fail `p`.
  //  */
  // def dropWhile(p: A => Boolean): Pull[F,Nothing,Handle[F,A]] =
  //   this.receive { (chunk, h) =>
  //     chunk.indexWhere(!p(_)) match {
  //       case Some(0) => Pull.pure(h push chunk)
  //       case Some(i) => Pull.pure(h push chunk.drop(i))
  //       case None    => h.dropWhile(p)
  //     }
  //   }

  /** Writes all inputs to the output of the returned `Pull`. */
  def echo: Pull[F,O,Unit] = Pull.loop[F,O,Handle[F,O]](_.echoChunk)(this)

  /** Reads a single element from the input and emits it to the output. Returns the new `Handle`. */
  def echo1: Pull[F,O,Option[Handle[F,O]]] =
    this.receive1 { (o, h) => Pull.output1(o) >> Pull.pure(h) }

  /** Reads the next available chunk from the input and emits it to the output. Returns the new `Handle`. */
  def echoChunk: Pull[F,O,Option[Handle[F,O]]] =
    this.receive { (c, h) => Pull.output(c) >> Pull.pure(h) }

  // /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  // def fetchN(n: Int): Pull[F,Nothing,Handle[F,A]] =
  //   awaitN(n) map { case (buf, h) => buf.reverse.foldLeft(h)(_ push _) }
  //
  // /** Awaits the next available element where the predicate returns true. */
  // def find(f: A => Boolean): Pull[F,Nothing,(A,Handle[F,A])] =
  //   this.receive { (chunk, h) =>
  //     chunk.indexWhere(f) match {
  //       case None => h.find(f)
  //       case Some(a) if a + 1 < chunk.size => Pull.pure((chunk(a), h.push(chunk.drop(a + 1))))
  //       case Some(a) => Pull.pure((chunk(a), h))
  //     }
  //   }
  //
  // /**
  //  * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
  //  * result to the output of the supplied `Pull` when the stream has no more values.
  //  */
  // def fold[B](z: B)(f: (B, A) => B): Pull[F,Nothing,B] =
  //   await.optional flatMap {
  //     case Some((c, h)) => h.fold(c.foldLeft(z)(f))(f)
  //     case None => Pull.pure(z)
  //   }
  //
  // /**
  //  * Folds all inputs using the supplied binary operator, and writes the final result to the output of
  //  * the supplied `Pull` when the stream has no more values.
  //  */
  // def fold1[A2 >: A](f: (A2, A2) => A2): Pull[F,Nothing,A2] =
  //   this.receive1 { (o, h) => h.fold[A2](o)(f) }
  //
  // /** Writes a single `true` value if all input matches the predicate, `false` otherwise. */
  // def forall(p: A => Boolean): Pull[F,Nothing,Boolean] = {
  //   await1.optional flatMap {
  //     case Some((a, h)) =>
  //       if (!p(a)) Pull.pure(false)
  //       else h.forall(p)
  //     case None => Pull.pure(true)
  //   }
  // }
  //
  // /** Returns the last element of the input, if non-empty. */
  // def last: Pull[F,Nothing,Option[A]] = {
  //   def go(prev: Option[A]): Handle[F,A] => Pull[F,Nothing,Option[A]] =
  //     h => h.await.optional.flatMap {
  //       case None => Pull.pure(prev)
  //       case Some((c, h)) => go(c.foldLeft(prev)((_,a) => Some(a)))(h)
  //     }
  //   go(None)(this)
  // }

  /** Like [[await]] but does not consume the segment (i.e., the segment is pushed back). */
  def peek: Pull[F,Nothing,Option[(Segment[O,Unit],Handle[F,O])]] =
    this.receive { (hd, tl) => Pull.pure((hd, tl.push(hd))) }

  /** Like [[await1]] but does not consume the element (i.e., the element is pushed back). */
  def peek1: Pull[F,Nothing,Option[(O,Handle[F,O])]] =
    this.receive1 { (hd, tl) => Pull.pure((hd, tl.push1(hd))) }

  // // /**
  // //  * Like [[await]], but runs the `await` asynchronously. A `flatMap` into
  // //  * inner `Pull` logically blocks until this await completes.
  // //  */
  // // def prefetch[F2[_]](implicit sub: Sub1[F,F2], F: Effect[F2], ec: ExecutionContext): Pull[F2,Nothing,Pull[F2,Nothing,Handle[F2,A]]] =
  // //   awaitAsync map { fut =>
  // //     fut.pull flatMap { p =>
  // //       p map { case (hd, h) => h push hd }
  // //     }
  // //   }

  /** Emits the first `n` elements of the input and return the new `Handle`. */
  def take(n: Long): Pull[F,O,Option[Handle[F,O]]] =
    if (n <= 0) Pull.pure(Some(this))
    else awaitLimit(n).flatMapOpt { s =>
      Pull.segment(s).flatMap { case (rem, tl) =>
        if (rem > 0) tl.take(rem) else Pull.pure(None)
      }
    }

  // /** Emits the last `n` elements of the input. */
  // def takeRight(n: Long): Pull[F,Nothing,Vector[A]]  = {
  //   def go(acc: Vector[A])(h: Handle[F,A]): Pull[F,Nothing,Vector[A]] = {
  //     h.awaitN(if (n <= Int.MaxValue) n.toInt else Int.MaxValue, true).optional.flatMap {
  //       case None => Pull.pure(acc)
  //       case Some((cs, h)) =>
  //         val vector = cs.toVector.flatMap(_.toVector)
  //         go(acc.drop(vector.length) ++ vector)(h)
  //     }
  //   }
  //   if (n <= 0) Pull.pure(Vector())
  //   else go(Vector())(this)
  // }
  //
  // /** Like `takeWhile`, but emits the first value which tests false. */
  // def takeThrough(p: A => Boolean): Pull[F,A,Handle[F,A]] =
  //   this.receive { (chunk, h) =>
  //     chunk.indexWhere(!p(_)) match {
  //       case Some(a) => Pull.output(chunk.take(a+1)) >> Pull.pure(h.push(chunk.drop(a+1)))
  //       case None => Pull.output(chunk) >> h.takeThrough(p)
  //     }
  //   }
  //
  // /**
  //  * Emits the elements of this `Handle` until the predicate `p` fails,
  //  * and returns the new `Handle`. If non-empty, the returned `Handle` will have
  //  * a first element `i` for which `p(i)` is `false`. */
  // def takeWhile(p: A => Boolean): Pull[F,A,Handle[F,A]] =
  //   this.receive { (chunk, h) =>
  //     chunk.indexWhere(!p(_)) match {
  //       case Some(0) => Pull.pure(h.push(chunk))
  //       case Some(a) => Pull.output(chunk.take(a)) >> Pull.pure(h.push(chunk.drop(a)))
  //       case None    => Pull.output(chunk) >> h.takeWhile(p)
  //     }
  //   }

  /** Converts this handle to a handle of the specified subtype. */
  implicit def covary[F2[x]>:F[x]]: Handle[F2,O] = this.asInstanceOf[Handle[F2,O]]

  override def toString = s"Handle($buffer, $underlying)"
}

object Handle {

  private val empty_ : Handle[Nothing,Nothing] = new Handle(Nil, Stream.empty)

  /** Empty handle. */
  def empty[F[_],O]: Handle[F,O] = empty_

  implicit class HandleInvariantEffectOps[F[_],+O](private val self: Handle[F,O]) extends AnyVal {

    /** Apply `f` to the next available `Segment`. */
    def receive[O2,R](f: (Segment[O,Unit],Handle[F,O]) => Pull[F,O2,R]): Pull[F,O2,Option[R]] =
      self.await.flatMapOpt { case (hd, tl) => f(hd, tl).map(Some(_)) }

    /** Apply `f` to the next available element. */
    def receive1[O2,R](f: (O,Handle[F,O]) => Pull[F,O2,R]): Pull[F,O2,Option[R]] =
      self.await1.flatMapOpt { case (hd, tl) => f(hd, tl).map(Some(_)) }

    /** Apply `f` to the next available chunk, or `None` if the input is exhausted. */
    def receiveOption[O2,R](f: Option[(Segment[O,Unit],Handle[F,O])] => Pull[F,O2,R]): Pull[F,O2,R] =
      self.await.flatMap(f)

    /** Apply `f` to the next available element, or `None` if the input is exhausted. */
    def receive1Option[O2,R](f: Option[(O,Handle[F,O])] => Pull[F,O2,R]): Pull[F,O2,R] =
      self.await1.flatMap(f)
  }
}
