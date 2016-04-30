package fs2

private[fs2] trait pull1 {
  import Stream.Handle

  // nb: methods are in alphabetical order

  /** Await the next available `Chunk` from the `Handle`. The `Chunk` may be empty. */
  def await[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]] =
    _.await

  /** Await the next available nonempty `Chunk`. */
  def awaitNonempty[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]] =
    receive { case s@(hd #: tl) => if (hd.isEmpty) awaitNonempty(tl) else Pull.pure(s) }

  /** Await a single element from the `Handle`. */
  def await1[F[_],I]: Handle[F,I] => Pull[F,Nothing,Step[I,Handle[F,I]]] =
    _.await1

  /** Like `await`, but return a `Chunk` of no more than `maxChunkSize` elements. */
  def awaitLimit[F[_],I](maxChunkSize: Int)
    : Handle[F,I] => Pull[F,Nothing,Step[Chunk[I],Handle[F,I]]]
    = _.await.map { s =>
        if (s.head.size <= maxChunkSize) s
        else s.head.take(maxChunkSize) #: s.tail.push(s.head.drop(maxChunkSize))
      }

  /** Return a `List[Chunk[I]]` from the input whose combined size has a maximum value `n`. */
  def awaitN[F[_],I](n: Int, allowFewer: Boolean = false)
    : Handle[F,I] => Pull[F,Nothing,Step[List[Chunk[I]],Handle[F,I]]]
    = h =>
        if (n <= 0) Pull.pure(List() #: h)
        else for {
          hd #: tl <- awaitLimit(n)(h)
          hd2 #: tl <- _awaitN0(n, allowFewer)(hd #: tl)
        } yield (hd :: hd2) #: tl
  private def _awaitN0[F[_],I](n: Int, allowFewer: Boolean)
  : Step[Chunk[I],Handle[F,I]] => Pull[F, Nothing, Step[List[Chunk[I]], Handle[F,I]]] = { case (hd #: tl) =>
      val next = awaitN(n - hd.size, allowFewer)(tl)
      if (allowFewer) next.optional.map(_.getOrElse(List() #: Handle.empty)) else next
  }

  /** Await the next available chunk from the input, or `None` if the input is exhausted. */
  def awaitOption[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[Chunk[I],Handle[F,I]]]] =
    h => h.await.map(Some(_)) or Pull.pure(None)

  /** Await the next available element from the input, or `None` if the input is exhausted. */
  def await1Option[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[Step[I,Handle[F,I]]]] =
    h => h.await1.map(Some(_)) or Pull.pure(None)

  /** Await the next available non-empty chunk from the input, or `None` if the input is exhausted. */
  def awaitNonemptyOption[F[_],I]: Handle[F, I] => Pull[F, Nothing, Option[Step[Chunk[I], Handle[F,I]]]] =
    h => awaitNonempty(h).map(Some(_)) or Pull.pure(None)

  /** Copy the next available chunk to the output. */
  def copy[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { case chunk #: h => Pull.output(chunk) >> Pull.pure(h) }

  /** Copy the next available element to the output. */
  def copy1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive1 { case hd #: h => Pull.output1(hd) >> Pull.pure(h) }

  /** Drop the first `n` elements of the input `Handle`, and return the new `Handle`. */
  def drop[F[_], I](n: Long): Handle[F, I] => Pull[F, Nothing, Handle[F, I]] =
    h =>
      if (n <= 0) Pull.pure(h)
      else awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
        case chunk #: h => drop(n - chunk.size)(h)
      }

  /**
   * Drop the elements of the input `Handle` until the predicate `p` fails, and return the new `Handle`.
   * If nonempty, the first element of the returned `Handle` will fail `p`.
   */
  def dropWhile[F[_], I](p: I => Boolean): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    receive { case chunk #: h =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h push chunk)
        case Some(i) => Pull.pure(h push chunk.drop(i))
        case None    => dropWhile(p)(h)
      }
    }

  /** Write all inputs to the output of the returned `Pull`. */
  def echo[F[_],I]: Handle[F,I] => Pull[F,I,Nothing] =
    h => echoChunk(h) flatMap (echo)

  /** Read a single element from the input and emit it to the output. Returns the new `Handle`. */
  def echo1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive1 { case i #: h => Pull.output1(i) >> Pull.pure(h) }

  /** Read the next available chunk from the input and emit it to the output. Returns the new `Handle`. */
  def echoChunk[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { case c #: h => Pull.output(c) >> Pull.pure(h) }

  /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  def fetchN[F[_],I](n: Int): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    h => awaitN(n)(h) map { case buf #: h => buf.reverse.foldLeft(h)(_ push _) }

  /** Await the next available element where the predicate returns true */
  def find[F[_],I](f: I => Boolean): Handle[F,I] => Pull[F,Nothing,Step[I,Handle[F,I]]] =
    receive { case chunk #: h =>
      chunk.indexWhere(f) match {
        case None => find(f).apply(h)
        case Some(i) if i + 1 < chunk.size => Pull.pure(chunk(i) #: h.push(chunk.drop(i + 1)))
        case Some(i) => Pull.pure(chunk(i) #: h)
      }
    }

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
   * result to the output of the supplied `Pull` when the stream has no more values.
   */
  def fold[F[_],I,O](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,Nothing,O] =
    h => h.await.optional flatMap {
      case Some(c #: h) => fold(c.foldLeft(z)(f))(f)(h)
      case None => Pull.pure(z)
    }

  /**
   * Folds all inputs using the supplied binary operator, and writes the final result to the output of
   * the supplied `Pull` when the stream has no more values.
   */
  def fold1[F[_],I](f: (I, I) => I): Handle[F,I] => Pull[F,Nothing,I] =
    receive1 { case o #: h => fold(o)(f)(h) }

  /** Write a single `true` value if all input matches the predicate, false otherwise */
  def forall[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,Nothing,Boolean] = {
    h => h.await1.optional flatMap {
      case Some(i #: h) =>
        if (!p(i)) Pull.pure(false)
        else forall(p).apply(h)
      case None => Pull.pure(true)
    }
  }

  /** Return the last element of the input `Handle`, if nonempty. */
  def last[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[I]] = {
    def go(prev: Option[I]): Handle[F,I] => Pull[F,Nothing,Option[I]] =
      h => h.await.optional.flatMap {
        case None => Pull.pure(prev)
        case Some(c #: h) => go(c.foldLeft(prev)((_,i) => Some(i)))(h)
      }
    go(None)
  }

  /**
   * Like `[[await]]`, but runs the `await` asynchronously. A `flatMap` into
   * inner `Pull` logically blocks until this await completes.
   */
  def prefetch[F[_]:Async,I](h: Handle[F,I]): Pull[F,Nothing,Pull[F,Nothing,Handle[F,I]]] =
    h.awaitAsync map { fut =>
      fut.force flatMap { p =>
        p map { case hd #: h => h push hd }
      }
    }

  /** Apply `f` to the next available `Chunk`. */
  def receive[F[_],I,O,R](f: Step[Chunk[I],Handle[F,I]] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    _.await.flatMap(f)

  /** Apply `f` to the next available element. */
  def receive1[F[_],I,O,R](f: Step[I,Handle[F,I]] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    _.await1.flatMap(f)

  /** Apply `f` to the next available chunk, or `None` if the input is exhausted. */
  def receiveOption[F[_],I,O,R](f: Option[Step[Chunk[I],Handle[F,I]]] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    awaitOption(_).flatMap(f)

  /** Apply `f` to the next available element, or `None` if the input is exhausted. */
  def receive1Option[F[_],I,O,R](f: Option[Step[I,Handle[F,I]]] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    await1Option(_).flatMap(f)

  def receiveNonemptyOption[F[_],I,O,R](f: Option[Step[Chunk[I], Handle[F,I]]] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    awaitNonemptyOption(_).flatMap(f)

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long)(h: Handle[F,I]): Pull[F,I,Handle[F,I]] =
    if (n <= 0) Pull.pure(h)
    else Pull.awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
      case chunk #: h => Pull.output(chunk) >> take(n - chunk.size.toLong)(h)
    } 

  /** Emits the last `n` elements of the input. */
  def takeRight[F[_],I](n: Long)(h: Handle[F,I]): Pull[F,Nothing,Vector[I]]  = {
    def go(acc: Vector[I])(h: Handle[F,I]): Pull[F,Nothing,Vector[I]] = {
      Pull.awaitN(if (n <= Int.MaxValue) n.toInt else Int.MaxValue, true)(h).optional.flatMap {
        case None => Pull.pure(acc)
        case Some(cs #: h) =>
          val vector = cs.toVector.flatMap(_.toVector)
          go(acc.drop(vector.length) ++ vector)(h)
      }
    }
    if (n <= 0) Pull.pure(Vector())
    else go(Vector())(h)
  }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { case chunk #: h =>
      chunk.indexWhere(!p(_)) match {
        case Some(i) => Pull.output(chunk.take(i+1)) >> Pull.pure(h.push(chunk.drop(i+1)))
        case None => Pull.output(chunk) >> takeThrough(p)(h)
      }
    }

  /**
   * Emit the elements of the input `Handle` until the predicate `p` fails,
   * and return the new `Handle`. If nonempty, the returned `Handle` will have
   * a first element `i` for which `p(i)` is `false`. */
  def takeWhile[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { case chunk #: h =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h.push(chunk))
        case Some(i) => Pull.output(chunk.take(i)) >> Pull.pure(h.push(chunk.drop(i)))
        case None    => Pull.output(chunk) >> takeWhile(p)(h)
      }
    }
}
