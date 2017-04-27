package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import cats.{ Eq, Functor }
import cats.effect.Effect
import cats.implicits._

import fs2.async.mutable.Queue
import fs2.util.{ Attempt, Free, Sub1 }

/** Generic implementations of common pipes. */
object pipe {

  // nb: methods are in alphabetical order

  /** Behaves like the identity function, but requests `n` elements at a time from the input. */
  def buffer[F[_],I](n: Int): Pipe[F,I,I] =
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

  /**
   * Emits only elements that are distinct from their immediate predecessors,
   * using natural equality for comparison.
   */
  def changes[F[_],I](implicit eq: Eq[I]): Pipe[F,I,I] =
    filterWithPrevious(eq.neqv)

  /**
   * Emits only elements that are distinct from their immediate predecessors
   * according to `f`, using natural equality for comparison.
   *
   * Note that `f` is called for each element in the stream multiple times
   * and hence should be fast (e.g., an accessor). It is not intended to be
   * used for computationally intensive conversions. For such conversions,
   * consider something like: `src.map(i => (i, f(i))).changesBy(_._2).map(_._1)`
   */
  def changesBy[F[_],I,I2](f: I => I2)(implicit eq: Eq[I2]): Pipe[F,I,I] =
    filterWithPrevious((i1, i2) => eq.neqv(f(i1), f(i2)))

  /** Outputs chunks with a limited maximum size, splitting as necessary. */
  def chunkLimit[F[_],I](n: Int): Pipe[F,I,NonEmptyChunk[I]] =
    _ repeatPull { _.awaitLimit(n) flatMap { case (chunk, h) => Pull.output1(chunk) as h } }

  /** Outputs a list of chunks, the total size of all chunks is limited and split as necessary. */
  def chunkN[F[_],I](n: Int, allowFewer: Boolean = true): Pipe[F,I,List[NonEmptyChunk[I]]] =
    _ repeatPull { _.awaitN(n, allowFewer) flatMap { case (chunks, h) => Pull.output1(chunks) as h }}

  /** Outputs all chunks from the input `Handle`. */
  def chunks[F[_],I]: Pipe[F,I,NonEmptyChunk[I]] =
    _ repeatPull { _.await.flatMap { case (chunk, h) => Pull.output1(chunk) as h }}

  /** Map/filter simultaneously. Calls `collect` on each `Chunk` in the stream. */
  def collect[F[_],I,I2](pf: PartialFunction[I, I2]): Pipe[F,I,I2] =
    mapChunks(_.collect(pf))

  /** Emits the first element of the Stream for which the partial function is defined. */
  def collectFirst[F[_],I,I2](pf: PartialFunction[I, I2]): Pipe[F,I,I2] =
    _ pull { h => h.find(pf.isDefinedAt) flatMap { case (i, h) => Pull.output1(pf(i)) }}

  /** Skips the first element that matches the predicate. */
  def delete[F[_],I](p: I => Boolean): Pipe[F,I,I] =
    _ pull { h => h.takeWhile((i:I) => !p(i)).flatMap(_.drop(1)).flatMap(_.echo) }

  /** Drops `n` elements of the input, then echoes the rest. */
  def drop[F[_],I](n: Long): Pipe[F,I,I] =
    _ pull { h => h.drop(n).flatMap(_.echo) }

  /** Drops the last element. */
  def dropLast[F[_],I]: Pipe[F,I,I] =
    dropLastIf(_ => true)

  /** Drops the last element if the predicate evaluates to true. */
  def dropLastIf[F[_],I](p: I => Boolean): Pipe[F,I,I] = {
    def go(last: Chunk[I]): Handle[F,I] => Pull[F,I,Unit] = {
      _.receiveOption {
        case Some((chunk, h)) => Pull.output(last) >> go(chunk)(h)
        case None =>
          val i = last(last.size - 1)
          if (p(i)) Pull.output(last.take(last.size - 1))
          else Pull.output(last)
      }
    }
    _.pull { _.receiveOption {
      case Some((c, h)) => go(c)(h)
      case None => Pull.done
    }}
  }

  /** Emits all but the last `n` elements of the input. */
  def dropRight[F[_],I](n: Int): Pipe[F,I,I] = {
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

  /** Debounce the stream with a minimum period of `d` between each element */
  def debounce[F[_], I](d: FiniteDuration)(implicit F: Effect[F], scheduler: Scheduler, ec: ExecutionContext): Pipe[F, I, I] = {
    def go(i: I, h1: Handle[F, I]): Pull[F, I, Nothing] = {
      time.sleep[F](d).open.flatMap { h2 =>
        h2.awaitAsync.flatMap { l =>
          h1.awaitAsync.flatMap { r =>
            (l race r).pull.flatMap {
              case Left(_) => Pull.output1(i) >> r.pull.flatMap(identity).flatMap {
                case (hd, tl) => go(hd.last, tl)
              }
              case Right(r) => r.optional.flatMap {
                case Some((hd, tl)) => go(hd.last, tl)
                case None => Pull.output1(i) >> Pull.done
              }
            }
          }
        }
      }
    }
    _.pull { h => h.await.flatMap { case (hd, tl) => go(hd.last, tl) } }
  }

  /** Drops the elements of the input until the predicate `p` fails, then echoes the rest. */
  def dropWhile[F[_], I](p: I => Boolean): Pipe[F,I,I] =
    _ pull { h => h.dropWhile(p).flatMap(_.echo) }

  private def _evalScan0[F[_], O, I](z: O)(f: (O, I) => F[O]): Handle[F, I] => Pull[F, O, Handle[F, I]] =
    h => h.await1Option.flatMap {
      case Some((i, h)) => Pull.eval(f(z, i)).flatMap { o =>
        Pull.output(Chunk.seq(Vector(z, o))) >> _evalScan1(o)(f)(h)
      }
      case None => Pull.output(Chunk.singleton(z)) as Handle.empty
    }

  private def _evalScan1[F[_], O, I](z: O)(f: (O, I) => F[O]): Handle[F, I] => Pull[F, O, Handle[F, I]] =
    h => h.await1.flatMap {
      case (i, h) => Pull.eval(f(z, i)).flatMap { o =>
        Pull.output(Chunk.singleton(o)) >> _evalScan1(o)(f)(h)
      }}

  /** Like `[[pipe.scan]]`, but accepts a function returning an F[_] */
  def evalScan[F[_], O, I](z: O)(f: (O, I) => F[O]): Pipe[F, I, O] =
    _ pull (_evalScan0(z)(f))

  /** Emits `true` as soon as a matching element is received, else `false` if no input matches */
  def exists[F[_], I](p: I => Boolean): Pipe[F,I,Boolean] =
    _ pull { h => h.forall(!p(_)) flatMap { i => Pull.output1(!i) }}

  /** Emits only inputs which match the supplied predicate. */
  def filter[F[_], I](f: I => Boolean): Pipe[F,I,I] =
    mapChunks(_ filter f)

  /**
   * Like `filter`, but the predicate `f` depends on the previously emitted and
   * current elements.
   */
  def filterWithPrevious[F[_],I](f: (I, I) => Boolean): Pipe[F,I,I] = {
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
  def find[F[_],I](f: I => Boolean): Pipe[F,I,I] =
    _ pull { h => h.find(f).flatMap { case (o, h) => Pull.output1(o) }}

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator,
   * and emits a single element stream.
   */
  def fold[F[_],I,O](z: O)(f: (O, I) => O): Pipe[F,I,O] =
    _ pull { h => h.fold(z)(f).flatMap(Pull.output1) }

  /**
   * Folds all inputs using the supplied binary operator, and emits a single-element
   * stream, or the empty stream if the input is empty.
   */
  def fold1[F[_],I](f: (I, I) => I): Pipe[F,I,I] =
    _ pull { h => h.fold1(f).flatMap(Pull.output1) }

  /**
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received.
   */
  def forall[F[_], I](p: I => Boolean): Pipe[F,I,Boolean] =
    _ pull { h => h.forall(p) flatMap Pull.output1 }

  /**
   * Partitions the input into a stream of chunks according to a discriminator function.
   * Each chunk is annotated with the value of the discriminator function applied to
   * any of the chunk's elements.
   */
  def groupBy[F[_], K, V](f: V => K)(implicit eq: Eq[K]): Pipe[F, V, (K, Vector[V])] = {

    def go(current: Option[(K, Vector[V])]):
        Handle[F, V] => Pull[F, (K, Vector[V]), Unit] = h => {

      h.receiveOption {
        case Some((chunk, h)) =>
          val (k1, out) = current.getOrElse((f(chunk(0)), Vector[V]()))
          doChunk(chunk, h, k1, out, Vector.empty)
        case None =>
          val l = current.map { case (k1, out) => Pull.output1((k1, out)) } getOrElse Pull.pure(())
          l >> Pull.done
      }
    }

    @annotation.tailrec
    def doChunk(chunk: Chunk[V], h: Handle[F, V], k1: K, out: Vector[V], acc: Vector[(K, Vector[V])]):
        Pull[F, (K, Vector[V]), Unit] = {

      val differsAt = chunk.indexWhere(v => eq.neqv(f(v), k1)).getOrElse(-1)
      if (differsAt == -1) {
        // whole chunk matches the current key, add this chunk to the accumulated output
        val newOut: Vector[V] = out ++ chunk.toVector
        if (acc.isEmpty) {
          go(Some((k1, newOut)))(h)
        } else {
          // potentially outputs one additional chunk (by splitting the last one in two)
          Pull.output(Chunk.indexedSeq(acc)) >> go(Some((k1, newOut)))(h)
        }
      } else {
        // at least part of this chunk does not match the current key, need to group and retain chunkiness
        var startIndex = 0
        var endIndex = differsAt

        // split the chunk into the bit where the keys match and the bit where they don't
        val matching = chunk.take(differsAt)
        val newOut: Vector[V] = out ++ matching.toVector
        val nonMatching = chunk.drop(differsAt)
        // nonMatching is guaranteed to be non-empty here, because we know the last element of the chunk doesn't have
        // the same key as the first
        val k2 = f(nonMatching(0))
        doChunk(nonMatching, h, k2, Vector[V](), acc :+ ((k1, newOut)))
      }
    }

    in => in.pull(go(None))
  }

  /** Emits the first element of this stream (if non-empty) and then halts. */
  def head[F[_],I]: Pipe[F,I,I] =
    take(1)

  /** Emits the specified separator between every pair of elements in the source stream. */
  def intersperse[F[_],I](separator: I): Pipe[F,I,I] =
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

  /** Identity pipe - every input is output unchanged. */
  def id[F[_],I]: Pipe[F,I,I] = s => s

  /** Returns the last element of the input `Handle`, if non-empty. */
  def last[F[_],I]: Pipe[F,I,Option[I]] =
    _ pull { h => h.last.flatMap { o => Pull.output1(o) }}

  /** Returns the last element of the input `Handle` if non-empty, otherwise li. */
  def lastOr[F[_],I](li: => I): Pipe[F,I,I] =
    _ pull { h => h.last.flatMap {
      case Some(o) => Pull.output1(o)
      case None => Pull.output1(li)
    }}

  /**
   * Applies the specified pure function to each input and emits the result.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],I,O](f: I => O): Pipe[F,I,O] = _ map f

  /** Outputs a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O]): Pipe[F,I,O] =
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
  def mapAccumulate[F[_],S,I,O](init: S)(f: (S,I) => (S,O)): Pipe[F,I,(S,O)] =
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
  def prefetch[F[_]:Effect,I](implicit ec: ExecutionContext): Pipe[F,I,I] =
    _ repeatPull { _.receive {
      case (hd, tl) => tl.prefetch flatMap { p => Pull.output(hd) >> p }}}

  /**
   * Modifies the chunk structure of the underlying stream, emitting potentially unboxed
   * chunks of `n` elements. If `allowFewer` is true, the final chunk of the stream
   * may be less than `n` elements. Otherwise, if the final chunk is less than `n`
   * elements, it is dropped.
   */
  def rechunkN[F[_],I](n: Int, allowFewer: Boolean = true): Pipe[F,I,I] =
    in => chunkN(n, allowFewer)(in).flatMap { chunks => Stream.chunk(Chunk.concat(chunks)) }

  /** Rethrows any `Left(err)`. Preserves chunkiness. */
  def rethrow[F[_],I]: Pipe[F,Attempt[I],I] =
    _.chunks.flatMap { es =>
      val errs = es collect { case Left(e) => e }
      val ok = es collect { case Right(i) => i }
      errs.uncons match {
        case None => Stream.chunk(ok)
        case Some((err, _)) => Stream.fail(err) // only first error is reported
      }
    }

  /** Alias for `[[pipe.fold1]]` */
  def reduce[F[_],I](f: (I, I) => I): Pipe[F,I,I] = fold1(f)

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
  def scan[F[_],I,O](z: O)(f: (O, I) => O): Pipe[F,I,O] = {
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
  def scan1[F[_],I](f: (I, I) => I): Pipe[F,I,I] =
    _ pull { _.receive1 { (o, h) => _scan0(o)(f)(h) }}

  /** Emits the given values, then echoes the rest of the input. */
  def shiftRight[F[_],I](head: I*): Pipe[F,I,I] =
    _ pull { h => h.push(Chunk.indexedSeq(Vector(head: _*))).echo }

  /**
   * Groups inputs in fixed size chunks by passing a "sliding window"
   * of size `n` over them. If the input contains less than or equal to
   * `n` elements, only one chunk of this size will be emitted.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4).sliding(2).toList
   * res0: List[Vector[Int]] = List(Vector(1, 2), Vector(2, 3), Vector(3, 4))
   * }}}
   * @throws scala.IllegalArgumentException if `n` <= 0
   */
  def sliding[F[_],I](n: Int): Pipe[F,I,Vector[I]] = {
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
   * Breaks the input into chunks where the delimiter matches the predicate.
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

  /** Emits all elements of the input except the first one. */
  def tail[F[_],I]: Pipe[F,I,I] =
    drop(1)

  /** Emits the first `n` elements of the input `Handle` and returns the new `Handle`. */
  def take[F[_],I](n: Long): Pipe[F,I,I] =
    _.pull(_.take(n))

  /** Emits the last `n` elements of the input. */
  def takeRight[F[_],I](n: Long): Pipe[F,I,I] =
    _ pull { h => h.takeRight(n).flatMap(is => Pull.output(Chunk.indexedSeq(is))) }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[F[_],I](f: I => Boolean): Pipe[F,I,I] =
    _.pull(_.takeThrough(f))

  /** Emits the longest prefix of the input for which all elements test true according to `f`. */
  def takeWhile[F[_],I](f: I => Boolean): Pipe[F,I,I] =
    _.pull(_.takeWhile(f))

  /** Converts the input to a stream of 1-element chunks. */
  def unchunk[F[_],I]: Pipe[F,I,I] =
    _ repeatPull { _.receive1 { case (i, h) => Pull.output1(i) as h }}

  /** Filters any 'None'. */
  def unNone[F[_], I]: Pipe[F, Option[I], I] = _.collect { case Some(i) => i }

  /**
   * Halts the input stream at the first `None`.
   *
   * @example {{{
   * scala> Stream[Pure, Option[Int]](Some(1), Some(2), None, Some(3), None).unNoneTerminate.toList
   * res0: List[Int] = List(1, 2)
   * }}}
   */
  def unNoneTerminate[F[_],I]: Pipe[F,Option[I],I] =
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
  def vectorChunkN[F[_],I](n: Int, allowFewer: Boolean = true): Pipe[F,I,Vector[I]] =
    chunkN(n, allowFewer) andThen (_.map(i => i.foldLeft(Vector.empty[I])((v, c) => v ++ c.iterator)))

  /** Zips the elements of the input `Handle` with its indices, and returns the new `Handle` */
  def zipWithIndex[F[_],I]: Pipe[F,I,(I,Int)] = {
    mapAccumulate[F, Int, I, I](-1) {
      case (i, x) => (i + 1, x)
    } andThen(_.map(_.swap))
  }

  /**
   * Zips the elements of the input `Handle` with its next element wrapped into `Some`, and returns the new `Handle`.
   * The last element is zipped with `None`.
   */
  def zipWithNext[F[_], I]: Pipe[F,I,(I,Option[I])] = {
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
   * Zips the elements of the input `Handle` with its previous element wrapped into `Some`, and returns the new `Handle`.
   * The first element is zipped with `None`.
   */
  def zipWithPrevious[F[_], I]: Pipe[F,I,(Option[I],I)] = {
    mapAccumulate[F, Option[I], I, (Option[I], I)](None) {
      case (prev, next) => (Some(next), (prev, next))
    } andThen(_.map { case (_, prevNext) => prevNext })
  }

  /**
   * Zips the elements of the input `Handle` with its previous and next elements wrapped into `Some`, and returns the new `Handle`.
   * The first element is zipped with `None` as the previous element,
   * the last element is zipped with `None` as the next element.
   */
  def zipWithPreviousAndNext[F[_], I]: Pipe[F,I,(Option[I],I,Option[I])] = {
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
  def zipWithScan[F[_],I,S](z: S)(f: (S, I) => S): Pipe[F,I,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s)) }.map(_._2)

  /**
   * Zips the input with a running total according to `S`, including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   * {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_ + _.length).toList
   * res0: List[(String, Int)] = List((uno,3), (dos,6), (tres,10), (cuatro,16))
   * }}}
   *
   * @see [[zipWithScan]]
   */
  def zipWithScan1[F[_],I,S](z: S)(f: (S, I) => S): Pipe[F,I,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s2)) }.map(_._2)

  /** Converts a pure pipe to an effectful pipe of the specified type. */
  def covary[F[_],I,O](s: Pipe[Pure,I,O]): Pipe[F,I,O] =
    s.asInstanceOf[Pipe[F,I,O]]

  // Combinators for working with pipes

  /** Creates a [[Stepper]], which allows incrementally stepping a pure pipe. */
  def stepper[I,O](s: Pipe[Pure,I,O]): Stepper[I,O] = {
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

  /**
   * Allows stepping of a pure pipe. Each invocation of [[step]] results in
   * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
   * failed with an exception, it emitted a chunk of output, or it is awaiting input.
   */
  sealed trait Stepper[-A,+B] {
    @annotation.tailrec
    final def step: Stepper.Step[A,B] = this match {
      case Stepper.Suspend(s) => s().step
      case _ => this.asInstanceOf[Stepper.Step[A,B]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]

    /** Algebra describing the result of stepping a pure pipe. */
    sealed trait Step[-A,+B] extends Stepper[A,B]
    /** Pipe indicated it is done. */
    final case object Done extends Step[Any,Nothing]
    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any,Nothing]
    /** Pipe emitted a chunk of elements. */
    final case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
    /** Pipe is awaiting input. */
    final case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  }

  /**
   * Pass elements of `s` through both `f` and `g`, then combine the two resulting streams.
   * Implemented by enqueueing elements as they are seen by `f` onto a `Queue` used by the `g` branch.
   * USE EXTREME CARE WHEN USING THIS FUNCTION. Deadlocks are possible if `combine` pulls from the `g`
   * branch synchronously before the queue has been populated by the `f` branch.
   *
   * The `combine` function receives an `F[Int]` effect which evaluates to the current size of the
   * `g`-branch's queue.
   *
   * When possible, use one of the safe combinators like `[[observe]]`, which are built using this function,
   * in preference to using this function directly.
   */
  def diamond[F[_],A,B,C,D](s: Stream[F,A])
    (f: Pipe[F,A, B])
    (qs: F[Queue[F,Option[Chunk[A]]]], g: Pipe[F,A,C])
    (combine: Pipe2[F,B,C,D])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,D] = {
      Stream.eval(qs) flatMap { q =>
      Stream.eval(async.semaphore[F](1)) flatMap { enqueueNoneSemaphore =>
      Stream.eval(async.semaphore[F](1)) flatMap { dequeueNoneSemaphore =>
      combine(
        f {
          val enqueueNone: F[Unit] =
            enqueueNoneSemaphore.tryDecrement.flatMap { decremented =>
              if (decremented) q.enqueue1(None)
              else F.pure(())
            }
          s.repeatPull {
            _.receiveOption {
              case Some((a, h)) =>
                Pull.eval(q.enqueue1(Some(a))) >> Pull.output(a).as(h)
              case None =>
                Pull.eval(enqueueNone) >> Pull.done
            }
          }.onFinalize(enqueueNone)
        },
        {
          val drainQueue: Stream[F,Nothing] =
            Stream.eval(dequeueNoneSemaphore.tryDecrement).flatMap { dequeue =>
              if (dequeue) pipe.unNoneTerminate(q.dequeue).drain
              else Stream.empty
            }

          (pipe.unNoneTerminate(
            q.dequeue.
              evalMap { c =>
                if (c.isEmpty) dequeueNoneSemaphore.tryDecrement.as(c)
                else F.pure(c)
              }).
              flatMap { c => Stream.chunk(c) }.
              through(g) ++ drainQueue
          ).onError { t => drainQueue ++ Stream.fail(t) }
        }
      )
    }}}
  }

  /** Queue based version of [[join]] that uses the specified queue. */
  def joinQueued[F[_],A,B](q: F[Queue[F,Option[Chunk[A]]]])(s: Stream[F,Pipe[F,A,B]])(implicit F: Effect[F], ec: ExecutionContext): Pipe[F,A,B] = in => {
    for {
      done <- Stream.eval(async.signalOf(false))
      q <- Stream.eval(q)
      b <- in.chunks.map(Some(_)).evalMap(q.enqueue1)
             .drain
             .onFinalize(q.enqueue1(None))
             .onFinalize(done.set(true)) merge done.interrupt(s).flatMap { f =>
               f(pipe.unNoneTerminate(q.dequeue) flatMap Stream.chunk)
             }
    } yield b
  }

  /** Asynchronous version of [[join]] that queues up to `maxQueued` elements. */
  def joinAsync[F[_]:Effect,A,B](maxQueued: Int)(s: Stream[F,Pipe[F,A,B]])(implicit ec: ExecutionContext): Pipe[F,A,B] =
    joinQueued[F,A,B](async.boundedQueue(maxQueued))(s)

  /**
   * Joins a stream of pipes in to a single pipe.
   * Input is fed to the first pipe until it terminates, at which point input is
   * fed to the second pipe, and so on.
   */
  def join[F[_]:Effect,A,B](s: Stream[F,Pipe[F,A,B]])(implicit ec: ExecutionContext): Pipe[F,A,B] =
    joinQueued[F,A,B](async.synchronousQueue)(s)

  /** Synchronously send values through `sink`. */
  def observe[F[_]:Effect,A](s: Stream[F,A])(sink: Sink[F,A])(implicit ec: ExecutionContext): Stream[F,A] =
    diamond(s)(identity)(async.synchronousQueue, sink andThen (_.drain))(pipe2.merge)

  /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
  def observeAsync[F[_]:Effect,A](s: Stream[F,A], maxQueued: Int)(sink: Sink[F,A])(implicit ec: ExecutionContext): Stream[F,A] =
    diamond(s)(identity)(async.boundedQueue(maxQueued), sink andThen (_.drain))(pipe2.merge)
}
