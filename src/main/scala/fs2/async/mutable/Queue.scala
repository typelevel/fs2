package fs2.async.mutable

import fs2._
import fs2.async.AsyncExt.Change

import fs2.async.{immutable, AsyncExt}



/**
 * Asynchronous queue interface. Operations are all nonblocking in their
 * implementations, but may be 'semantically' blocking. For instance,
 * a queue may have a bound on its size, in which case enqueuing may
 * block until there is an offsetting dequeue.
 */
trait Queue[F[_],A] {


  /**
   * Enqueue one element in this `Queue`. Returns size after enqueue of this elements.
   * If the size is == 0, indicates that enqueued elements was consumed right away, otherwise it indicates
   * size of the queue.
   * Please note this will get completed _after_ `a` has been successfully enqueued.
   * In case queue is `bounded` this waits to complete until queue is drained so there is space to enqueue `a`
   * @param a `A` to enqueue
   */
  def enqueueOne(a: A): F[Int]

  /**
   * Enqueue multiple `A` values in this queue. This has same semantics as sequencing
   * repeated calls to `enqueueOne`.
   */
  def enqueueAll(xa: Seq[A]): F[Int]

  /**
   * Provides a process that dequeue from this queue.
   * When multiple consumers dequeue from this queue,
   * they dequeue in first-come, first-serve order.
   *
   * Please use `Topic` instead of `Queue` when all subscribers
   * need to see each value enqueued.
   *
   * This process is equivalent to `dequeueBatch(1)`.
   */
  def dequeue: Stream[F, A]

  /**
   * Provides a process that dequeues in chunks.  Whenever *n* elements
   * are available in the queue, `min(n, limit)` elements will be dequeud
   * and produced as a single `Chunk`.  Note that this naturally does not
   * *guarantee* that `limit` items are returned in every chunk.  If only
   * one element is available, that one element will be returned in its own
   * Chunk.  This method basically just allows a consumer to "catch up"
   * to a rapidly filling queue in the case where some sort of batching logic
   * is applicable.
   */
  def dequeueBatch(limit: Int): Stream[F, A]

  /**
   * Equivalent to dequeueBatch with an infinite limit.  Only use this
   * method if your underlying algebra (`A`) has some sort of constant
   * time "natural batching"!  If processing a chunk of size n is linearly
   * more expensive than processing a chunk of size 1, you should always
   * use dequeueBatch with some small limit, otherwise you will disrupt
   * fairness in the nondeterministic merge combinators.
   */
  def dequeueAvailable: Stream[F, A]

  /**
   * The time-varying size of this `Queue`. This signal refreshes
   * only when size changes. Offsetting enqueues and de-queues may
   * not result in refreshes.
   */
  def size: Stream[F,immutable.Signal[F,Int]]

  /**
   * The size bound on the queue.
   * Returns None if the queue is unbounded.
   */
  def upperBound: Option[Int]

  /**
   * Returns the available number of entries in the queue.
   * Always returns `Int.MaxValue` when the queue is unbounded.
   */
  def available: Stream[F,immutable.Signal[F,Int]]

  /**
   * Returns `true` when the queue has reached its upper size bound.
   * Always returns `false` when the queue is unbounded.
   */
  def full: Stream[F,immutable.Signal[F,Boolean]]




}

object Queue {

    private type DeQueue[F[_],A] = (Int,Seq[A] => F[Unit]) // Int represent preferred chunk size
    private type EnQueue[F[_],A] = (Seq[A], Int => F[Unit])


  /**
    * Internal state of the queue
    * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
    * @param deq      A list of De-Queuer (filled up when queue is empty)
    * @param enq      A list of En-Queuer (filled up when size of the queue is at or over bounds
    * @tparam F
    * @tparam A
    */
    private case class State[F[_],A](
      queue: Vector[A]
      , deq:Vector[DeQueue[F,A]]
      , enq:Vector[EnQueue[F,A]]
    )




    def apply[F[_],A](bound:Int = 0)(implicit F:AsyncExt[F]):Stream[F,Queue[F,A]] = {
      Signal(0).flatMap { szSignal =>
        Stream.eval {
          F.bind(F.ref[State[F,A]]) { qref =>
            F.map(F.set(qref)(F.pure(State(Vector.empty,Vector.empty,Vector.empty)))) { _ =>

              // When over bounds, this add enq task
              // enq is defined as function taking Int of current queue size `after` enqueue to signal it back as result
              // note that the resulting Int => F[Unit] is called only AFTER all `A` has been successfully enqueued.
              def registerEnqueue(s:State[F,A],ref:F.Ref[Int],xa:Seq[A]):State[F,A] =
                s.copy(enq = s.enq :+ (xa -> (F.setPure(ref)(_))))

              // Register deque when there is nothing to be dequeued
              // note resulting Seq[A] => F[Unit] is called whenever at least one `A` is available,
              // however when more `A` are available then up to `sz` is returned
              def registerDequeue(s:State[F,A],ref:F.Ref[Seq[A]], sz:Int):State[F,A] =
                s.copy(deq = s.deq :+ (sz -> (F.setPure(ref)(_))))


              // Tries to satisfy any awaiting de-queuers (recursively). Honors preferred size of dequeuer for chunking.
              // Results in left, if there are remaining items after satisfying all the dequeuers
              // and in right when input has been exhausted and there are still dequeuers available
              // if, input has been fully consumed and all dequeuers satisfied emits empty on left
              def fillDequeuer(xa:Seq[A], deq:Vector[DeQueue[F,A]]): F[Either[Seq[A],Vector[DeQueue[F,A]]]] = {
                deq.headOption match {
                  case None => F.pure(Left(xa))
                  case Some((sz,dqF)) =>
                    if (xa.isEmpty) F.pure(Right(deq))
                    else {
                      val (dqA, remA) = xa.splitAt(sz)
                      F.bind(dqF(dqA))(_ => fillDequeuer(remA, deq.tail)) //todo: runs on single thread likely fork (S()) needed (dqF)? May we fork later in deque stream ?
                    }
                }
              }

              // recursively tries to satisfy deque operation so that acc contains up to `require` items
              // it consults the `enq` to obtain more items for waiting enqueuers, while still respecting bound
              // Results in tuple containing accumulated `A` with size <= require,  and enqueuers that were not used yet if any
              // may return both empty, in that case queue is empty.
              def satisfyDequeue(require:Int, enq:Vector[EnQueue[F,A]],acc:Vector[A]): F[(Vector[A],Vector[EnQueue[F,A]])] = {
                if (acc.size >= require) F.pure(acc -> enq)
                else {
                  enq.headOption match {
                    case None => F.pure(acc -> Vector.empty)
                    case Some((xa,enqF)) =>
                      val (nacc, rem) = (acc ++ xa).splitAt(require)
                      if (rem.isEmpty) F.bind(enqF(0))(_ => satisfyDequeue(require,enq.tail, nacc))
                      else F.pure((nacc,(rem,enqF) +: enq.tail))
                  }
                }
              }

              // Signals size change of queue, if that has changed
              def signalSize(s:State[F,A], ns:State[F,A]) : F[Unit] = {
                if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
                else F.pure(())
              }

              // enqueueAll implementation
              // tries to satisfy or waiting dequeuers on this enqueue and/or attaches the remainder to the queue
              // also updates size of the queue.
              def _enqueueAll(xa: Seq[A]): F[Int] = {
                if (xa.isEmpty) F.map(F.get(qref))(_.queue.size)
                else {
                  F.bind(F.ref[Int]) { enqRef =>
                    val modify =
                      F.modify(qref) { s =>
                        if (s.enq.nonEmpty) F.pure(registerEnqueue(s, enqRef, xa))
                        else {
                          F.bind(fillDequeuer(xa,s.deq)) {
                            case Right(deq) => F.bind(F.setPure(enqRef)(0))(_ => F.pure(s.copy(queue =Vector.empty, deq = deq)))
                            case Left(remains) =>
                              lazy val (queue,rem) = remains.splitAt(bound)
                              if (bound <= 0 || rem.isEmpty) F.bind(F.setPure(enqRef)(remains.size))(_ => F.pure(s.copy( queue = remains.toVector, deq = Vector.empty)))
                              else  F.pure(registerEnqueue(s.copy(queue = queue.toVector, deq = Vector.empty),enqRef,rem))
                          }
                        }
                      }

                    F.bind(modify) { case Change(s,ns) =>
                      F.bind(signalSize(s,ns)) { _ =>
                        F.get(enqRef)
                      }
                    }
                  }
                }
              }

              // implementation of dequeueBatch
              // repeatedly dequeue from the queue, while completing any waiting enqueue on bounded queue
              // and taking the chunks up to `limit` that are available in queue
              def _dequeueBatch(limit: Int): Stream[F, A] = Stream.eval {
                F.bind(F.ref[Seq[A]]) { deqRef =>
                  val modify  =
                    F.modify(qref) { s =>
                      if (s.deq.nonEmpty) F.pure(registerDequeue(s, deqRef, limit))
                      else {
                        F.bind(satisfyDequeue(limit,s.enq,s.queue)) { case (deq1, enq1) =>
                          if (deq1.isEmpty) F.pure(registerDequeue(s, deqRef, limit))
                          else {
                            val (out,rem) = deq1.splitAt(limit)
                            lazy val require = if(bound <= 0) Int.MaxValue else bound
                            val fillQueue =
                              if (enq1.nonEmpty && rem.size < require) {
                                F.map(satisfyDequeue(require,enq1,rem)) { case (deq2,enq2) =>
                                  s.copy(queue = deq2, enq = enq2)
                                }
                              }
                              else {
                                F.pure(s.copy(queue = rem,enq = enq1))
                              }
                            F.bind(F.setPure(deqRef)(out)){ _ => fillQueue}
                          }
                        }

                      }
                    }

                    F.bind(modify){ case Change(s,ns) =>
                      F.bind(signalSize(s,ns)){ _ =>
                        F.map(F.get(deqRef))(Chunk.seq)
                      }
                    }
                }
              }.flatMap(Stream.chunk) ++ _dequeueBatch(limit)


              new Queue[F,A] {
                lazy val upperBound: Option[Int] = if (bound <= 0) None else Some(bound)

                def enqueueOne(a: A): F[Int] = enqueueAll(Seq(a))
                def enqueueAll(xa: Seq[A]): F[Int] = _enqueueAll(xa)
                def dequeueBatch(limit: Int): Stream[F, A] = _dequeueBatch(limit)
                def dequeue: Stream[F, A] = _dequeueBatch(1)
                def dequeueAvailable: Stream[F, A] = _dequeueBatch(Int.MaxValue)
                def size: Stream[F,immutable.Signal[F, Int]] = Stream(szSignal)
                def full: Stream[F,immutable.Signal[F, Boolean]] =
                  upperBound match {
                    case None => Signal[F,Boolean](false)
                    case Some(limit) => Stream(szSignal.map(_ >= limit))
                  }
                def available: Stream[F,immutable.Signal[F, Int]] = {
                  upperBound match {
                    case None => Signal[F,Int](Int.MaxValue)
                    case Some(limit) => Stream(szSignal.map(sz => (limit - sz) max 0))
                  }
                }
              }


            }
          }
        }

      }

    }



}