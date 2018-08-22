package fs2.async.mutable

import cats.Applicative
import cats.syntax.all._
import cats.instances.list._
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Deferred, Ref}
import fs2._
import fs2.internal.Token

object Broadcast {

  /**
    * Allows to broadcast `O` to multiple workers.
    *
    * As the elements arrive, they are "broadcasted" to all `workers` that started evaluation just before the
    * element was pulled from `source`. If the evaluation is in progress and `worker` will register in time
    * when other workers are working on last `O`, then in that case worker will receive that last `O` as first
    * element.
    *
    * Elements are pulled from `source` and next element is pulled when all workers are done
    * with processing previous one.
    *
    * This behaviour may slow down processing of incoming elements by faster workers. If this is not desired,
    * consider using any `buffer` combinator on workers to compensate for slower workers.
    *
    * Also to speed-up processing consider to have broadcsast operate on `Chunk[O]` instead on `O`.
    *
    * Usually this combinator is used together with parJoin, such as :
    *
    * {{{
    *   Stream(1,2,3,4).broadcast.map { worker =>
    *     worker.evalMap { o => IO.println(s"1:$o") }
    *   }.take(3).parJoinUnbounded.compile.drain.unsafeRunSync
    * }}}
    *
    * Note that in the example above the workers are not guaranteed to see all elements emitted. This is
    * due different subscription time of each worker and speed of emitting the elements by `source`.
    *
    * If this is not desired, consider using [[Stream.broadcastTo]] or [[Stream.broadcastThrough]] alternatives.
    *
    * Also the `take(3)` actually defines concurrency in this example b/c it says we are interested to see 3 workers.
    * Also note it is safe to `recycle` worker, that means you may evaluate given worker multiple times.
    *
    *
    * When `source` terminates, the resulting streams and workers are terminated once all elements so far pulled
    * from `source` are processed by all workers.
    *
    * When `source` fails, workers get interrupted and resulting streams fails too.
    *
    * @return
    */
  def apply[F[_]: Concurrent, O](source: Stream[F, O]): Stream[F, Stream[F, O]] = {

    sealed trait WS

    // Worker is processing `last` `O` available
    case object Working extends WS

    // Worker has comiited work on `last` O and is awaiting next `O`
    case class Comitted(deferred: Deferred[F, Option[O]]) extends WS

    sealed trait S

    /*
     * Workers are currently processing last `O`. When All workers will switch to  `ready`
     * from `working`. then `source` will be signalled to pull bext element
     */
    case class Working(
        source: Deferred[F, Unit],
        last: O,
        working: Set[Token],
        ready: Map[Token, Deferred[F, Option[O]]]
    ) extends S

    /*
     * Source is pulling next element, workers are awaiting.
     */
    case class Await(
        workers: Map[Token, Deferred[F, Option[O]]]
    ) extends S

    /*
     * Initially, before any worker registers, this indicates source is ready to pull.
     * Once first worker registers, then the state only alters between `Working` and `Await`
     * @param source
     */
    case class Init(
        source: Deferred[F, Unit]
    ) extends S

    /** source stream is done, no more elements to worker **/
    case object Done extends S

    Stream.eval {
      Ref.of[F, S](Await(Map.empty)).flatMap { state =>
        Deferred[F, Unit].map { done =>
          def worker: Stream[F, O] = {
            val token = new Token
            def getNext: F[Option[O]] =
              Deferred[F, Option[O]].flatMap { signal =>
                state
                  .modify[F[Option[O]]] {
                    case Init(source) =>
                      (Await(Map(token -> signal)),
                       Concurrent[F].start(source.complete(())) >> signal.get)
                    case Await(workers) =>
                      (Await(workers + (token -> signal)), signal.get)
                    case w: Working =>
                      if (w.working.contains(token)) {
                        // completed work, put to `await`
                        if (w.working.size == 1) {
                          // last worker, signal the source
                          (Await(w.ready + (token -> signal)),
                           Concurrent[F].start(w.source.complete(())) >> signal.get)
                        } else {
                          // await for others to complete
                          (w.copy(working = w.working - token, ready = w.ready + (token -> signal)),
                           signal.get)
                        }
                      } else {
                        // new registration, offer `O`
                        (w.copy(working = w.working + token), Applicative[F].pure(Some(w.last)))
                      }
                    case Done =>
                      (Done, Applicative[F].pure(None))
                  }
                  .flatten
              }

            def unregister: F[Unit] =
              state.modify {
                case s: Init        => (s, Applicative[F].unit)
                case Await(workers) => (Await(workers - token), Applicative[F].unit)
                case w: Working =>
                  if (w.working.contains(token)) {
                    if (w.working.size == 1 && w.ready.nonEmpty) {
                      (Await(w.ready), Concurrent[F].start(w.source.complete(())).void)
                    } else {
                      (w.copy(working = w.working - token), Applicative[F].unit)
                    }
                  } else {
                    (w.copy(ready = w.ready - token), Applicative[F].unit)
                  }
                case Done => (Done, Applicative[F].unit)
              }.flatten

            Stream
              .repeatEval(getNext)
              .interruptWhen(done.get.attempt)
              .unNoneTerminate
              .onFinalize(unregister)
          }

          def runner: Stream[F, Unit] = {
            def register: F[Unit] =
              Deferred[F, Unit].flatMap { signal =>
                state.modify {
                  case s @ Await(workers) =>
                    if (workers.nonEmpty) (s, Applicative[F].unit)
                    else (Init(signal), signal.get)
                  case other =>
                    (other,
                     Sync[F].raiseError[Unit](new Throwable(s"Invalid initial state: $other")))
                }.flatten
              }

            def offer(o: O): F[Unit] =
              Deferred[F, Unit].flatMap { signal =>
                state.modify {
                  case Await(workers) =>
                    def completeAll =
                      workers.values.toList.traverse_(w =>
                        Concurrent[F].start(w.complete(Some(o))).void)
                    (Working(signal, o, workers.keySet, Map.empty), completeAll)

                  case other =>
                    (other, Sync[F].raiseError[Unit](new Throwable(s"Invalid offer state: $other")))
                }.flatten
              }

            def signalDone: F[Unit] =
              state.modify {
                case Await(workers) =>
                  def completeAll =
                    workers.values.toList.traverse_(w => Concurrent[F].start(w.complete(None)).void)
                  (Done, completeAll)
                case w: Working =>
                  def completeAll =
                    w.ready.values.toList.traverse_(w => Concurrent[F].start(w.complete(None)).void)
                  (Done, completeAll)
                case other =>
                  (Done, Sync[F].raiseError[Unit](new Throwable(s"Invalid done state: $other")))
              }.flatten

            def complete: F[Unit] =
              state.get.flatMap {
                case Done => Applicative[F].unit // signals we completed normally
                case _ =>
                  done.complete(()) // error before we have completed, interrupt workers, they stop immediatelly
              }

            (
              Stream.eval(register) >>
                source.evalMap { offer } ++
                  Stream.eval_(signalDone)
            ).onFinalize { complete }
          }

          Stream.constant(worker).concurrently(runner)

        }
      }
    }.flatten

  }

}
