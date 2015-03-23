package scalaz.stream


import Cause._
import scalaz.{Right3, Middle3, Left3, \/-, -\/, Either3, \/}
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process._

package object nondeterminism {


  /**
   * Nondeterministic join of streams. Up to `maxOpen` processes may be
   * open at a time, and `maxQueued` bounds the amount of prefetching.
   *
   * If any process terminates abnormally, the whole join terminates with
   * that reason and all open processes are killed.
   * If the resulting process is killed or fails, or if downstream terminates
   * early, all the merged processes are killed.
   *
   * @param maxOpen     maximum simultaneous processes that concurrently merge. If eq 0 no limit.
   * @param maxQueued   maximum number of queued `A` before merged processes will back-off. If eq 0 no limit.
   * @param source
   * @param S
   * @tparam A
   * @return
   */
  def njoin[A](maxOpen: Int, maxQueued: Int)(source: Process[Task, Process[Task, A]])(implicit S: Strategy): Process[Task, A] = {
    sealed trait M
    case object Start extends M
    case class Delay(cont: Cont[Task, Process[Task, A]]) extends M
    case class Offer(p: Process[Task, A], cont: Cont[Task,Process[Task,A]]) extends M
    case class FinishedSource(rsn: Cause) extends M
    case class Finished(result: Throwable \/ Unit) extends M
    case class FinishedDown(cb: (Throwable \/ Unit) => Unit) extends M


    suspend {
      val q = if (maxQueued > 0)
        async.boundedQueue[A](maxQueued)(S)
      else
        async.unboundedQueue[A](S)

      val done = async.signalOf(false)(S)

      //keep state of master source
      var state: Either3[Cause, EarlyCause => Unit,  Cont[Task,Process[Task,A]]] =
        Either3.left3(End)

      //keep no of open processes
      var opened: Int = 0

      //if this mergeN is closed this is set to reason that caused mergeN to close
      var closed: Option[Cause] = None

      //keeps track of finalized streams and will issue callback
      //when all streams have finished.
      var completer: Option[(Throwable \/ Unit) => Unit] = None

      var actor: Actor[M] = null

      //evaluates next step of the source of processes
      def nextStep(from: Process[Task, Process[Task, A]]) = {
        Either3.middle3({
          from stepAsync {
            case -\/(rsn) =>
              actor ! FinishedSource(rsn)

            // contrary to popular belief (well, basically just *here*), stepAsync does not guarantee non-emptiness
            case \/-((Seq(), cont)) =>
              actor ! Delay(cont)

            case \/-((processes, cont)) =>
              val next = (c:Cause) => Trampoline.done(
                c.fold(emitAll(processes.tail) +: cont)(
                  early => Halt(early) +: cont
                )
              )
              actor ! Offer(processes.head, Cont(Vector(next)))
          }
        })
      }

      // fails the signal should cause all streams to terminate

      def fail(cause: Cause): Unit = {
        closed = Some(cause)
        S(done.kill runAsync { _=> () })
        state = state match {
          case Middle3(interrupt) => S(interrupt(Kill)) ; Either3.middle3((_:Cause) => ())
          case Right3(cont) => nextStep(Halt(Kill) +: cont);  Either3.middle3((_:Cause) => ())
          case Left3(_) => state
        }
      }

      // starts the source evaluation
      def start: Task[Unit] =
        Task delay { actor ! Start }

      def sourceDone = state.leftOr(false)(_=>true)

      def allDone : Boolean = opened <= 0 && sourceDone

      def completeIfDone: Unit = {
        if (allDone) {
          S(q.failWithCause(closed.getOrElse(End)) runAsync { _ => {} })
          completer.foreach { cb =>  S(cb(\/-(()))) }
          completer = None
          closed = closed orElse Some(End)
        }
      }

      actor = Actor[M]({m =>
        closed.fold(m match {
          case Delay(cont) =>
            state = nextStep(cont.continue)

          // next merged process is available
          // run it with chance to interrupt
          // and give chance to start next process if not bounded
          case Offer(p, cont) =>
            opened = opened + 1
            if (maxOpen <= 0 || opened < maxOpen) state = nextStep(cont.continue)
            else state = Either3.right3(cont)

            //runs the process with a chance to interrupt it using signal `done`
            //interrupt is done via killing the done signal.
            //enqueue action needs to be interruptible, otherwise the process will never halt
            //if the outer process is terminated while the queue is full and an enqueue is pending
            //note that here we convert `Kill` to exception to distinguish form normal and
            //killed behaviour of upstream termination
            done.discrete.wye(p.flatMap(a => eval_(q.enqueueOne(a))))(wye.interrupt)
            .onHalt{
              case Kill => Halt(Error(Terminated(Kill)))
              case cause => Halt(cause)
            }
            .run.runAsync { res =>
              S(actor ! Finished(res))
            }

          //finished the `upstream` normally but still have some open processes to merge
          //update state and wait for processes to merge
          case FinishedSource(End) if opened > 0 =>
            state = Either3.left3(End)

          // finished upstream and no processes are running, terminate downstream
          // or the cause is early cause and shall kill all the remaining processes
          case FinishedSource(rsn) =>
            state = Either3.left3(rsn)
            fail(rsn)
            completeIfDone

          // merged process terminated with failure
          // kill all the open processes including the `source`
          case Finished(-\/(rsn)) =>
            val cause = rsn match {
              case Terminated(Kill) => Kill
              case _ => Error(rsn)
            }
            opened = opened - 1
            fail(cause)
            completeIfDone

          // One of the processes terminated w/o failure
          // Finish merge if the opened == 0 and upstream terminated
          // or give an opportunity to start next merged process if the maxOpen was hit
          case Finished(\/-(_)) =>
            opened = opened - 1
            state = state match {
              case Right3(cont)
                if maxOpen <= 0 || opened < maxOpen => nextStep(cont.continue)
              case Left3(End) if opened == 0 => fail(End) ; state
              case _                     => state
            }
            completeIfDone

          // `Downstream` of the merge terminated
          // Kill all upstream processes and eventually the source.
          case FinishedDown(cb) =>
            fail(Kill)
            if (allDone) S(cb(\/-(())))
            else completer = Some(cb)

          // Start evaluation of the source.
          case Start =>
            // Assignment to `state` must be performed inside this actor to prevent races.
            state = nextStep(source)

        })(rsn => m match {
          //join is closed, next p is ignored and source is killed
          case Offer(_, cont) => nextStep(Halt(Kill) +: cont)
          case FinishedSource(cause) => state = Either3.left3(cause) ; completeIfDone
          case Finished(_) => opened = opened - 1; completeIfDone
          case FinishedDown(cb) =>
            if (allDone) S(cb(\/-(())))
            else completer = Some(cb)
          case Start => ()
          case Delay(cont) => nextStep(cont.continue)
        })

      })


      (eval_(start) ++ q.dequeue)
      .onComplete(eval_(Task.async[Unit] { cb => actor ! FinishedDown(cb) }))
    }

  }

}
