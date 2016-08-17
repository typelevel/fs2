package fs2

import fs2.util.{Async,Attempt,Free,Functor,Sub1}

object pipe {

  // nb: methods are in alphabetical order

  /** Behaves like the identity function, but requests `n` elements at a time from the input. */
  def buffer[F[_],I](n: Int): Stream[F,I] => Stream[F,I] =
    _.repeatPull { _.awaitN(n, true).flatMap { case (chunks, h) =>
      chunks.foldLeft(Pull.pure(()): Pull[F,I,Unit]) { (acc, c) => acc >> Pull.output(c) } as h
    }}

  /** Behaves like the identity stream, but emits no output until the source is exhausted. */
  def bufferAll[F[_],I]: Pipe[F,I,I] = bufferBy(_ => true)

  /**
   * Behaves like the identity stream, but requests elements from its
   * input in blocks that end whenever the predicate switches from true to false.
   */
  def bufferBy[F[_],I](f: I => Boolean): Pipe[F,I,I] = {
    def go(buffer: Vector[Chunk[I]], last: Boolean): Handle[F,I] => Pull[F,I,Unit] = {
      _.receiveOption {
        case Some((chunk, h)) =>
          val (out, buf, last) = {
            chunk.foldLeft((Vector.empty[Chunk[I]], Vector.empty[I], false)) { case ((out, buf, last), i) =>
              val cur = f(i)
              if (!f(i) && last) (out :+ Chunk.indexedSeq(buf :+ i), Vector.empty, cur)
              else (out, buf :+ i, cur)
            }
          }
          if (out.isEmpty) {
            go(buffer :+ Chunk.indexedSeq(buf), last)(h)
          } else {
            (buffer ++ out).foldLeft(Pull.pure(()): Pull[F,I,Unit]) { (acc, c) => acc >> Pull.output(c) } >>
              go(Vector(Chunk.indexedSeq(buf)), last)(h)
          }

        case None =>
          buffer.foldLeft(Pull.pure(()): Pull[F,I,Unit]) { (acc, c) => acc >> Pull.output(c) }
      }
    }
    _.pull { h => go(Vector.empty, false)(h) }
  }

  /** Outputs first value, and then any changed value from the last value. `eqf` is used for equality. **/
  def changes[F[_],I](eqf:(I,I) => Boolean): Stream[F,I] => Stream[F,I] =
    zipWithPrevious andThen collect {
      case (None,next) => next
      case (Some(last), next) if !eqf(last,next) => next
    }

  /** Outputs chunks with a limited maximum size, splitting as necessary. */
  def chunkLimit[F[_],I](n: Int): Stream[F,I] => Stream[F,Chunk[I]] =
    _ repeatPull { _.awaitLimit(n) flatMap { case (chunk, h) => Pull.output1(chunk) as h } }

  /** Outputs a list of chunks, the total size of all chunks is limited and split as necessary. */
  def chunkN[F[_],I](n: Int, allowFewer: Boolean = true): Stream[F,I] => Stream[F,List[Chunk[I]]] =
    _ repeatPull { _.awaitN(n, allowFewer) flatMap { case (chunks, h) => Pull.output1(chunks) as h }}

  /** Output all chunks from the input `Handle`. */
  def chunks[F[_],I]: Stream[F,I] => Stream[F,Chunk[I]] =
    _ repeatPull { _.await.flatMap { case (chunk, h) => Pull.output1(chunk) as h }}

  /** Map/filter simultaneously. Calls `collect` on each `Chunk` in the stream. */
  def collect[F[_],I,I2](pf: PartialFunction[I, I2]): Stream[F,I] => Stream[F,I2] =
    mapChunks(_.collect(pf))

  /** Emits the first element of the Stream for which the partial function is defined. */
  def collectFirst[F[_],I,I2](pf: PartialFunction[I, I2]): Stream[F,I] => Stream[F,I2] =
    _ pull { h => h.find(pf.isDefinedAt) flatMap { case (i, h) => Pull.output1(pf(i)) }}

  /** Skip the first element that matches the predicate. */
  def delete[F[_],I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.takeWhile((i:I) => !p(i)).flatMap(_.drop(1)).flatMap(_.echo) }

  /**
   * Emits only elements that are distinct from their immediate predecessors,
   * using natural equality for comparison.
   */
  def distinctConsecutive[F[_],I]: Stream[F,I] => Stream[F,I] =
    filterWithPrevious((i1, i2) => i1 != i2)

  /**
   * Emits only elements that are distinct from their immediate predecessors
   * according to `f`, using natural equality for comparison.
   *
   * Note that `f` is called for each element in the stream multiple times
   * and hence should be fast (e.g., an accessor). It is not intended to be
   * used for computationally intensive conversions. For such conversions,
   * consider something like: `src.map(i => (i, f(i))).distinctConsecutiveBy(_._2).map(_._1)`
   */
  def distinctConsecutiveBy[F[_],I,I2](f: I => I2): Stream[F,I] => Stream[F,I] =
    filterWithPrevious((i1, i2) => f(i1) != f(i2))

  /** Drop `n` elements of the input, then echo the rest. */
  def drop[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.drop(n).flatMap(_.echo) }

  /** Drops the last element. */
  def dropLast[F[_],I]: Pipe[F,I,I] =
    dropLastIf(_ => true)

  /** Drops the last element if the predicate evaluates to true. */
  def dropLastIf[F[_],I](p: I => Boolean): Pipe[F,I,I] = {
    def go(last: Chunk[I]): Handle[F,I] => Pull[F,I,Unit] = {
      _.receiveNonEmptyOption {
        case Some((chunk, h)) => Pull.output(last) >> go(chunk)(h)
        case None =>
          val i = last(last.size - 1)
          if (p(i)) Pull.output(last.take(last.size - 1))
          else Pull.output(last)
      }
    }
    _.pull { _.receiveNonEmptyOption {
      case Some((c, h)) => go(c)(h)
      case None => Pull.done
    }}
  }

  /** Emits all but the last `n` elements of the input. */
  def dropRight[F[_],I](n: Int): Stream[F,I] => Stream[F,I] = {
    if (n <= 0) identity
    else {
      def go(acc: Vector[I]): Handle[F,I] => Pull[F,I,Unit] = {
        _.receive {
          case (chunk, h) =>
            val all = acc ++ chunk.toVector
            Pull.output(Chunk.indexedSeq(all.dropRight(n))) >> go(all.takeRight(n))(h)
        }
      }
      _ pull go(Vector.empty)
    }
  }

  /** Drop the elements of the input until the predicate `p` fails, then echo the rest. */
  def dropWhile[F[_], I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.dropWhile(p).flatMap(_.echo) }

  /** Emits `true` as soon as a matching element is received, else `false` if no input matches */
  def exists[F[_], I](p: I => Boolean): Stream[F, I] => Stream[F, Boolean] =
    _ pull { h => h.forall(!p(_)) flatMap { i => Pull.output1(!i) }}

  /** Emit only inputs which match the supplied predicate. */
  def filter[F[_], I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    mapChunks(_ filter f)

  /**
   * Like `filter`, but the predicate `f` depends on the previously emitted and
   * current elements.
   */
  def filterWithPrevious[F[_],I](f: (I, I) => Boolean): Stream[F,I] => Stream[F,I] = {
    def go(last: I): Handle[F,I] => Pull[F,I,Unit] =
      _.receive { (c, h) =>
        // Check if we can emit this chunk unmodified
        val (allPass, newLast) = c.foldLeft((true, last)) { case ((acc, last), i) =>
          (acc && f(last, i), i)
        }
        if (allPass) {
          Pull.output(c) >> go(newLast)(h)
        } else {
          val (acc, newLast) = c.foldLeft((Vector.empty[I], last)) { case ((acc, last), i) =>
            if (f(last, i)) (acc :+ i, i)
            else (acc, last)
          }
          Pull.output(Chunk.indexedSeq(acc)) >> go(newLast)(h)
        }
      }
    _ pull { h => h.receive1 { (i, h) => Pull.output1(i) >> go(i)(h) } }
  }

  /** Emits the first input (if any) which matches the supplied predicate, to the output of the returned `Pull` */
  def find[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.find(f).flatMap { case (o, h) => Pull.output1(o) }}

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator,
   * and emits a single element stream.
   */
  def fold[F[_],I,O](z: O)(f: (O, I) => O): Stream[F,I] => Stream[F,O] =
    _ pull { h => h.fold(z)(f).flatMap(Pull.output1) }

  /**
   * Folds all inputs using the supplied binary operator, and emits a single-element
   * stream, or the empty stream if the input is empty.
   */
  def fold1[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.fold1(f).flatMap(Pull.output1) }

  /**
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received.
   */
  def forall[F[_], I](p: I => Boolean): Stream[F,I] => Stream[F,Boolean] =
    _ pull { h => h.forall(p) flatMap Pull.output1 }

  /** Emits the specified separator between every pair of elements in the source stream. */
  def intersperse[F[_],I](separator: I): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.echo1 flatMap Pull.loop { (h: Handle[F,I]) =>
      h.receive { case (chunk, h) =>
        val interspersed = {
          val bldr = Vector.newBuilder[I]
          chunk.toVector.foreach { i => bldr += separator; bldr += i }
          Chunk.indexedSeq(bldr.result)
        }
        Pull.output(interspersed) >> Pull.pure(h)
      }
    }}

  /** Write all inputs to the output of the returned `Pull`. */
  def id[F[_],I]: Stream[F,I] => Stream[F,I] =
    s => s

  /** Return the last element of the input `Handle`, if non-empty. */
  def last[F[_],I]: Stream[F,I] => Stream[F,Option[I]] =
    _ pull { h => h.last.flatMap { o => Pull.output1(o) }}

  /** Return the last element of the input `Handle` if non-empty, otherwise li. */
  def lastOr[F[_],I](li: => I): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.last.flatMap {
      case Some(o) => Pull.output1(o)
      case None => Pull.output1(li)
    }}

  /**
   * Write all inputs to the output of the returned `Pull`, transforming elements using `f`.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],I,O](f: I => O): Stream[F,I] => Stream[F,O] =
    _ map f

  /** Output a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O]): Stream[F,I] => Stream[F,O] =
    _ repeatPull { _.await.flatMap { case (chunk, h) => Pull.output(f(chunk)) as h }}

  /**
    * Maps a running total according to `S` and the input with the function `f`.
    *
    * @example {{{
    * scala> Stream("Hello", "World")
    *      |   .mapAccumulate(0)((l, s) => (l + s.length, s.head)).toVector
    * res0: Vector[(Int, Char)] = Vector((5,H), (10,W))
    * }}}
    */
  def mapAccumulate[F[_],S,I,O](init: S)(f: (S,I) => (S,O)): Stream[F,I] => Stream[F,(S,O)] =
    _.pull { _.receive { case (chunk, h) =>
      val f2 = (s: S, i: I) => {
        val (newS, newO) = f(s, i)
        (newS, (newS, newO))
      }
      val (s, o) = chunk.mapAccumulate(init)(f2)
      Pull.output(o) >> _mapAccumulate0(s)(f2)(h)
    }}
  private def _mapAccumulate0[F[_],S,I,O](init: S)(f: (S,I) => (S,(S,O))): Handle[F,I] => Pull[F,(S,O),Handle[F,I]] =
    _.receive { case (chunk, h) =>
      val (s, o) = chunk.mapAccumulate(init)(f)
      Pull.output(o) >> _mapAccumulate0(s)(f)(h)
    }

  /**
   * Behaves like `id`, but starts fetching the next chunk before emitting the current,
   * enabling processing on either side of the `prefetch` to run in parallel.
   */
  def prefetch[F[_]:Async,I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { _.receive {
      case (hd, tl) => tl.prefetch flatMap { p => Pull.output(hd) >> p }}}

  /**
   * Modifies the chunk structure of the underlying stream, emitting potentially unboxed
   * chunks of `n` elements. If `allowFewer` is true, the final chunk of the stream
   * may be less than `n` elements. Otherwise, if the final chunk is less than `n`
   * elements, it is dropped.
   */
  def rechunkN[F[_],I](n: Int, allowFewer: Boolean = true): Stream[F,I] => Stream[F,I] =
    in => chunkN(n, allowFewer)(in).flatMap { chunks => Stream.chunk(Chunk.concat(chunks)) }

  /** Rethrow any `Left(err)`. Preserves chunkiness. */
  def rethrow[F[_],I]: Stream[F,Attempt[I]] => Stream[F,I] =
    _.chunks.flatMap { es =>
      val errs = es collect { case Left(e) => e }
      val ok = es collect { case Right(i) => i }
      errs.uncons match {
        case None => Stream.chunk(ok)
        case Some((err, _)) => Stream.fail(err) // only first error is reported
      }
    }

  /** Alias for `[[pipe.fold1]]` */
  def reduce[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] = fold1(f)

  /**
   * Left fold which outputs all intermediate results. Example:
   *   `Stream(1,2,3,4) through pipe.scan(0)(_ + _) == Stream(0,1,3,6,10)`.
   *
   * More generally:
   *   `Stream().scan(z)(f) == Stream(z)`
   *   `Stream(x1).scan(z)(f) == Stream(z, f(z,x1))`
   *   `Stream(x1,x2).scan(z)(f) == Stream(z, f(z,x1), f(f(z,x1),x2))`
   *   etc
   *
   * Works in a chunky fashion, and creates a `Chunk.indexedSeq` for each converted chunk.
   */
  def scan[F[_],I,O](z: O)(f: (O, I) => O): Stream[F,I] => Stream[F,O] = {
    _ pull (_scan0(z)(f))
  }

  private def _scan0[F[_],O,I](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    h => h.await.optional flatMap {
      case Some((chunk, h)) =>
        val s = chunk.scanLeft(z)(f)
        Pull.output(s) >> _scan1(s(s.size - 1))(f)(h)
      case None => Pull.output(Chunk.singleton(z)) as Handle.empty
    }
  private def _scan1[F[_],O,I](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    _.receive { case (chunk, h) =>
      val s = chunk.scanLeft(z)(f).drop(1)
      Pull.output(s) >> _scan1(s(s.size - 1))(f)(h)
    }

  /**
   * Like `[[pipe.scan]]`, but uses the first element of the stream as the seed.
   */
  def scan1[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] =
    _ pull { _.receive1 { (o, h) => _scan0(o)(f)(h) }}

  /** Emit the given values, then echo the rest of the input. */
  def shiftRight[F[_],I](head: I*): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.push(Chunk.indexedSeq(Vector(head: _*))).echo }

  /**
   * Groups inputs in fixed size chunks by passing a "sliding window"
   * of size `n` over them. If the input contains less than or equal to
   * `n` elements, only one chunk of this size will be emitted.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4).sliding(2).toList
   * res0: List[Chunk[Int]] = List(Chunk(1, 2), Chunk(2, 3), Chunk(3, 4))
   * }}}
   * @throws IllegalArgumentException if `n` <= 0
   */
  def sliding[F[_],I](n: Int): Stream[F,I] => Stream[F,Vector[I]] = {
    require(n > 0, "n must be > 0")
    def go(window: Vector[I]): Handle[F,I] => Pull[F,Vector[I],Unit] = h => {
      h.receive {
        case (chunk, h) =>
          val out: Vector[Vector[I]] =
            chunk.toVector.scanLeft(window)((w, i) => w.tail :+ i).tail
          if (out.isEmpty) go(window)(h)
          else Pull.output(Chunk.indexedSeq(out)) >> go(out.last)(h)
      }
    }
    _ pull { h => h.awaitN(n, true).flatMap { case (chunks, h) =>
      val window = chunks.foldLeft(Vector.empty[I])(_ ++ _.toVector)
      Pull.output1(window) >> go(window)(h)
    }}
  }

  /**
   * Break the input into chunks where the delimiter matches the predicate.
   * The delimiter does not appear in the output. Two adjacent delimiters in the
   * input result in an empty chunk in the output.
   */
  def split[F[_],I](f: I => Boolean): Pipe[F,I,Vector[I]] = {
    def go(buffer: Vector[I]): Handle[F,I] => Pull[F,Vector[I],Unit] = {
      _.receiveOption {
        case Some((chunk, h)) =>
          chunk.indexWhere(f) match {
            case None =>
              go(buffer ++ chunk.toVector)(h)
            case Some(idx) =>
              val out = buffer ++ chunk.take(idx).toVector
              val carry = if (idx + 1 < chunk.size) chunk.drop(idx + 1) else Chunk.empty
              Pull.output1(out) >> go(Vector.empty)(h.push(carry))
          }
        case None =>
          if (buffer.nonEmpty) Pull.output1(buffer) else Pull.done
      }
    }
    _.pull(go(Vector.empty))
  }

  /** Writes the sum of all input elements, or zero if the input is empty. */
  def sum[F[_],I](implicit ev: Numeric[I]): Stream[F,I] => Stream[F,I] =
    fold(ev.zero)(ev.plus)

  /** Emits all elements of the input except the first one. */
  def tail[F[_],I]: Stream[F,I] => Stream[F,I] =
    drop(1)

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _.pull(_.take(n))

  /** Emits the last `n` elements of the input. */
  def takeRight[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull { h => h.takeRight(n).flatMap(is => Pull.output(Chunk.indexedSeq(is))) }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _.pull(_.takeThrough(f))

  /** Emit the longest prefix of the input for which all elements test true according to `f`. */
  def takeWhile[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _.pull(_.takeWhile(f))

  /** Convert the input to a stream of solely 1-element chunks. */
  def unchunk[F[_],I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { _.receive1 { case (i, h) => Pull.output1(i) as h }}

  /**
   * Halt the input stream at the first `None`.
   *
   * @example {{{
   * scala> unNoneTerminate(Stream(Some(1), Some(2), None, Some(3), None)).toVector
   * res0: Vector[Int] = Vector(1, 2)
   * }}}
   */
  def unNoneTerminate[F[_],I]: Stream[F,Option[I]] => Stream[F,I] =
    _ repeatPull { _.receive {
      case (hd, tl) =>
        val out = Chunk.indexedSeq(hd.toVector.takeWhile { _.isDefined }.collect { case Some(i) => i })
        if (out.size == hd.size) Pull.output(out) as tl
        else if (out.isEmpty) Pull.done
        else Pull.output(out) >> Pull.done
    }}

  /**
   * Groups inputs into separate `Vector` objects of size `n`.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).vectorChunkN(2).toVector
   * res0: Vector[Vector[Int]] = Vector(Vector(1, 2), Vector(3, 4), Vector(5))
   * }}}
   */
  def vectorChunkN[F[_],I](n: Int, allowFewer: Boolean = true): Stream[F,I] => Stream[F,Vector[I]] =
    chunkN(n, allowFewer) andThen (_.map(i => i.foldLeft(Vector.empty[I])((v, c) => v ++ c.iterator)))

  /** Zip the elements of the input `Handle` with its indices, and return the new `Handle` */
  def zipWithIndex[F[_],I]: Stream[F,I] => Stream[F,(I,Int)] = {
    mapAccumulate[F, Int, I, I](-1) {
      case (i, x) => (i + 1, x)
    } andThen(_.map(_.swap))
  }

  /**
    * Zip the elements of the input `Handle` with its next element wrapped into `Some`, and return the new `Handle`.
    * The last element is zipped with `None`.
    */
  def zipWithNext[F[_], I]: Stream[F, I] => Stream[F, (I, Option[I])] = {
    def go(last: I): Handle[F, I] => Pull[F, (I, Option[I]), Handle[F, I]] =
      _.receiveOption {
        case None => Pull.output1((last, None)) as Handle.empty
        case Some((chunk, h)) =>
          val (newLast, zipped) = chunk.mapAccumulate(last) {
            case (prev, next) => (next, (prev, Some(next)))
          }
          Pull.output(zipped) >> go(newLast)(h)
      }
    _.pull(_.receive1 { case (head, h) => go(head)(h) })
  }

  /**
    * Zip the elements of the input `Handle` with its previous element wrapped into `Some`, and return the new `Handle`.
    * The first element is zipped with `None`.
    */
  def zipWithPrevious[F[_], I]: Stream[F, I] => Stream[F, (Option[I], I)] = {
    mapAccumulate[F, Option[I], I, (Option[I], I)](None) {
      case (prev, next) => (Some(next), (prev, next))
    } andThen(_.map { case (_, prevNext) => prevNext })
  }

  /**
    * Zip the elements of the input `Handle` with its previous and next elements wrapped into `Some`, and return the new `Handle`.
    * The first element is zipped with `None` as the previous element,
    * the last element is zipped with `None` as the next element.
    */
  def zipWithPreviousAndNext[F[_], I]: Stream[F, I] => Stream[F, (Option[I], I, Option[I])] = {
    (zipWithPrevious[F, I] andThen zipWithNext[F, (Option[I], I)]) andThen {
      _.map {
        case ((prev, that), None) => (prev, that, None)
        case ((prev, that), Some((_, next))) => (prev, that, Some(next))
      }
    }
  }

  /**
   * Zips the input with a running total according to `S`, up to but not including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   * {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan1]]
   */
  def zipWithScan[F[_],I,S](z: S)(f: (S, I) => S): Stream[F,I] => Stream[F,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s)) }.map(_._2)

  /**
   * Zips the input with a running total according to `S`, including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   * {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan]]
   */
  def zipWithScan1[F[_],I,S](z: S)(f: (S, I) => S): Stream[F,I] => Stream[F,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s2)) }.map(_._2)

  // stepping a stream

  def covary[F[_],I,O](s: Stream[Pure,I] => Stream[Pure,O]): Pipe[F,I,O] =
    s.asInstanceOf[Pipe[F,I,O]]

  def stepper[I,O](s: Stream[Pure,I] => Stream[Pure,O]): Stepper[I,O] = {
    type Read[+R] = Option[Chunk[I]] => R
    def readFunctor: Functor[Read] = new Functor[Read] {
      def map[A,B](fa: Read[A])(g: A => B): Read[B]
        = fa andThen g
    }
    def prompts: Stream[Read,I] =
      Stream.eval[Read, Option[Chunk[I]]](identity).flatMap {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append(prompts)
      }

    def outputs: Stream[Read,O] = covary[Read,I,O](s)(prompts)
    def stepf(s: Handle[Read,O]): Free[Read, Option[(Chunk[O],Handle[Read, O])]]
    = s.buffer match {
        case hd :: tl => Free.pure(Some((hd, new Handle[Read,O](tl, s.stream))))
        case List() => s.stream.step.flatMap { s => Pull.output1(s) }
         .close.runFoldFree(None: Option[(Chunk[O],Handle[Read, O])])(
          (_,s) => Some(s))
      }
    def go(s: Free[Read, Option[(Chunk[O],Handle[Read, O])]]): Stepper[I,O] =
      Stepper.Suspend { () =>
        s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
          case Free.Unroll.Fail(err) => Stepper.Fail(err)
          case Free.Unroll.Pure(None) => Stepper.Done
          case Free.Unroll.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
          case Free.Unroll.Eval(recv) => Stepper.Await(chunk => go(recv(chunk)))
        }
      }
    go(stepf(new Handle[Read,O](List(), outputs)))
  }

  sealed trait Stepper[-A,+B] {
    @annotation.tailrec
    final def step: Stepper.Step[A,B] = this match {
      case Stepper.Suspend(s) => s().step
      case _ => this.asInstanceOf[Stepper.Step[A,B]]
    }
  }

  object Stepper {
    private[fs2] case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]

    sealed trait Step[-A,+B] extends Stepper[A,B]
    case object Done extends Step[Any,Nothing]
    case class Fail(err: Throwable) extends Step[Any,Nothing]
    case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
    case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  }
}
