package fs2

import scala.concurrent.ExecutionContext

import cats.effect.Effect
import cats.implicits._

import fs2.async.mutable.Queue

object Pipe {

  // /** Debounce the stream with a minimum period of `d` between each element */
  // def debounce[F[_], I](d: FiniteDuration)(implicit F: Effect[F], scheduler: Scheduler, ec: ExecutionContext): Pipe[F, I, I] = {
  //   def go(i: I, h1: Handle[F, I]): Pull[F, I, Nothing] = {
  //     time.sleep[F](d).open.flatMap { h2 =>
  //       h2.awaitAsync.flatMap { l =>
  //         h1.awaitAsync.flatMap { r =>
  //           (l race r).pull.flatMap {
  //             case Left(_) => Pull.output1(i) >> r.pull.flatMap(identity).flatMap {
  //               case (hd, tl) => go(hd.last, tl)
  //             }
  //             case Right(r) => r.optional.flatMap {
  //               case Some((hd, tl)) => go(hd.last, tl)
  //               case None => Pull.output1(i) >> Pull.done
  //             }
  //           }
  //         }
  //       }
  //     }
  //   }
  //   _.pull { h => h.await.flatMap { case (hd, tl) => go(hd.last, tl) } }
  // }

  // /**
  //  * Partitions the input into a stream of chunks according to a discriminator function.
  //  * Each chunk is annotated with the value of the discriminator function applied to
  //  * any of the chunk's elements.
  //  */
  // def groupBy[F[_], K, V](f: V => K)(implicit eq: Eq[K]): Pipe[F, V, (K, Vector[V])] = {
  //
  //   def go(current: Option[(K, Vector[V])]):
  //       Handle[F, V] => Pull[F, (K, Vector[V]), Unit] = h => {
  //
  //     h.uncons.flatMap {
  //       case Some((chunk, h)) =>
  //         val (k1, out) = current.getOrElse((f(chunk(0)), Vector[V]()))
  //         doChunk(chunk, h, k1, out, Vector.empty)
  //       case None =>
  //         val l = current.map { case (k1, out) => Pull.output1((k1, out)) } getOrElse Pull.pure(())
  //         l >> Pull.done
  //     }
  //   }
  //
  //   @annotation.tailrec
  //   def doChunk(chunk: Chunk[V], h: Handle[F, V], k1: K, out: Vector[V], acc: Vector[(K, Vector[V])]):
  //       Pull[F, (K, Vector[V]), Unit] = {
  //
  //     val differsAt = chunk.indexWhere(v => eq.neqv(f(v), k1)).getOrElse(-1)
  //     if (differsAt == -1) {
  //       // whole chunk matches the current key, add this chunk to the accumulated output
  //       val newOut: Vector[V] = out ++ chunk.toVector
  //       if (acc.isEmpty) {
  //         go(Some((k1, newOut)))(h)
  //       } else {
  //         // potentially outputs one additional chunk (by splitting the last one in two)
  //         Pull.output(Chunk.indexedSeq(acc)) >> go(Some((k1, newOut)))(h)
  //       }
  //     } else {
  //       // at least part of this chunk does not match the current key, need to group and retain chunkiness
  //       var startIndex = 0
  //       var endIndex = differsAt
  //
  //       // split the chunk into the bit where the keys match and the bit where they don't
  //       val matching = chunk.take(differsAt)
  //       val newOut: Vector[V] = out ++ matching.toVector
  //       val nonMatching = chunk.drop(differsAt)
  //       // nonMatching is guaranteed to be non-empty here, because we know the last element of the chunk doesn't have
  //       // the same key as the first
  //       val k2 = f(nonMatching(0))
  //       doChunk(nonMatching, h, k2, Vector[V](), acc :+ ((k1, newOut)))
  //     }
  //   }
  //
  //   in => in.pull(go(None))
  // }

  // /**
  //  * Modifies the chunk structure of the underlying stream, emitting potentially unboxed
  //  * chunks of `n` elements. If `allowFewer` is true, the final chunk of the stream
  //  * may be less than `n` elements. Otherwise, if the final chunk is less than `n`
  //  * elements, it is dropped.
  //  */
  // def rechunkN[F[_],I](n: Int, allowFewer: Boolean = true): Pipe[F,I,I] =
  //   in => chunkN(n, allowFewer)(in).flatMap { chunks => Stream.chunk(Chunk.concat(chunks)) }

  // /**
  //  * Zips the elements of the input `Handle` with its next element wrapped into `Some`, and returns the new `Handle`.
  //  * The last element is zipped with `None`.
  //  */
  // def zipWithNext[F[_], I]: Pipe[F,I,(I,Option[I])] = {
  //   def go(last: I): Handle[F, I] => Pull[F, (I, Option[I]), Handle[F, I]] =
  //     _.uncons.flatMap {
  //       case None => Pull.output1((last, None)) as Handle.empty
  //       case Some((chunk, h)) =>
  //         val (newLast, zipped) = chunk.mapAccumulate(last) {
  //           case (prev, next) => (next, (prev, Some(next)))
  //         }
  //         Pull.output(zipped) >> go(newLast)(h)
  //     }
  //   _.pull(_.receive1 { case (head, h) => go(head)(h) })
  // }
  //
  // /**
  //  * Zips the elements of the input `Handle` with its previous element wrapped into `Some`, and returns the new `Handle`.
  //  * The first element is zipped with `None`.
  //  */
  // def zipWithPrevious[F[_], I]: Pipe[F,I,(Option[I],I)] = {
  //   mapAccumulate[F, Option[I], I, (Option[I], I)](None) {
  //     case (prev, next) => (Some(next), (prev, next))
  //   } andThen(_.map { case (_, prevNext) => prevNext })
  // }
  //
  // /**
  //  * Zips the elements of the input `Handle` with its previous and next elements wrapped into `Some`, and returns the new `Handle`.
  //  * The first element is zipped with `None` as the previous element,
  //  * the last element is zipped with `None` as the next element.
  //  */
  // def zipWithPreviousAndNext[F[_], I]: Pipe[F,I,(Option[I],I,Option[I])] = {
  //   (zipWithPrevious[F, I] andThen zipWithNext[F, (Option[I], I)]) andThen {
  //     _.map {
  //       case ((prev, that), None) => (prev, that, None)
  //       case ((prev, that), Some((_, next))) => (prev, that, Some(next))
  //     }
  //   }
  // }
  //
  // /**
  //  * Zips the input with a running total according to `S`, up to but not including the current element. Thus the initial
  //  * `z` value is the first emitted to the output:
  //  * {{{
  //  * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
  //  * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
  //  * }}}
  //  *
  //  * @see [[zipWithScan1]]
  //  */
  // def zipWithScan[F[_],I,S](z: S)(f: (S, I) => S): Pipe[F,I,(I,S)] =
  //   _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s)) }.map(_._2)
  //
  // /**
  //  * Zips the input with a running total according to `S`, including the current element. Thus the initial
  //  * `z` value is the first emitted to the output:
  //  * {{{
  //  * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_ + _.length).toList
  //  * res0: List[(String, Int)] = List((uno,3), (dos,6), (tres,10), (cuatro,16))
  //  * }}}
  //  *
  //  * @see [[zipWithScan]]
  //  */
  // def zipWithScan1[F[_],I,S](z: S)(f: (S, I) => S): Pipe[F,I,(I,S)] =
  //   _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s2)) }.map(_._2)

  // // Combinators for working with pipes
  //
  // /** Creates a [[Stepper]], which allows incrementally stepping a pure pipe. */
  // def stepper[I,O](s: Pipe[Pure,I,O]): Stepper[I,O] = {
  //   type Read[+R] = Option[Chunk[I]] => R
  //   def readFunctor: Functor[Read] = new Functor[Read] {
  //     def map[A,B](fa: Read[A])(g: A => B): Read[B]
  //       = fa andThen g
  //   }
  //   def prompts: Stream[Read,I] =
  //     Stream.eval[Read, Option[Chunk[I]]](identity).flatMap {
  //       case None => Stream.empty
  //       case Some(chunk) => Stream.chunk(chunk).append(prompts)
  //     }
  //
  //   def outputs: Stream[Read,O] = covary[Read,I,O](s)(prompts)
  //   def stepf(s: Handle[Read,O]): Free[Read, Option[(Chunk[O],Handle[Read, O])]]
  //   = s.buffer match {
  //       case hd :: tl => Free.pure(Some((hd, new Handle[Read,O](tl, s.stream))))
  //       case List() => s.stream.step.flatMap { s => Pull.output1(s) }
  //        .close.runFoldFree(None: Option[(Chunk[O],Handle[Read, O])])(
  //         (_,s) => Some(s))
  //     }
  //   def go(s: Free[Read, Option[(Chunk[O],Handle[Read, O])]]): Stepper[I,O] =
  //     Stepper.Suspend { () =>
  //       s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
  //         case Free.Unroll.Fail(err) => Stepper.Fail(err)
  //         case Free.Unroll.Pure(None) => Stepper.Done
  //         case Free.Unroll.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
  //         case Free.Unroll.Eval(recv) => Stepper.Await(chunk => go(recv(chunk)))
  //       }
  //     }
  //   go(stepf(new Handle[Read,O](List(), outputs)))
  // }
  //
  // /**
  //  * Allows stepping of a pure pipe. Each invocation of [[step]] results in
  //  * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
  //  * failed with an exception, it emitted a chunk of output, or it is awaiting input.
  //  */
  // sealed trait Stepper[-A,+B] {
  //   @annotation.tailrec
  //   final def step: Stepper.Step[A,B] = this match {
  //     case Stepper.Suspend(s) => s().step
  //     case _ => this.asInstanceOf[Stepper.Step[A,B]]
  //   }
  // }
  //
  // object Stepper {
  //   private[fs2] final case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]
  //
  //   /** Algebra describing the result of stepping a pure pipe. */
  //   sealed trait Step[-A,+B] extends Stepper[A,B]
  //   /** Pipe indicated it is done. */
  //   final case object Done extends Step[Any,Nothing]
  //   /** Pipe failed with the specified exception. */
  //   final case class Fail(err: Throwable) extends Step[Any,Nothing]
  //   /** Pipe emitted a chunk of elements. */
  //   final case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
  //   /** Pipe is awaiting input. */
  //   final case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  // }

  /** Queue based version of [[join]] that uses the specified queue. */
  def joinQueued[F[_],A,B](q: F[Queue[F,Option[Segment[A,Unit]]]])(s: Stream[F,Pipe[F,A,B]])(implicit F: Effect[F], ec: ExecutionContext): Pipe[F,A,B] = in => {
    for {
      done <- Stream.eval(async.signalOf(false))
      q <- Stream.eval(q)
      b <- in.segments.map(Some(_)).evalMap(q.enqueue1)
             .drain
             .onFinalize(q.enqueue1(None))
             .onFinalize(done.set(true)) merge done.interrupt(s).flatMap { f =>
               f(q.dequeue.unNoneTerminate flatMap { x => Stream.segment(x) })
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
}
