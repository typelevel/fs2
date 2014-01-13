package scalaz.stream.merge

import scala._
import scala.collection.immutable.Queue
import scalaz.-\/
import scalaz.\/
import scalaz.\/-
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.stream.Process._
import scalaz.stream.actor.WyeActor
import scalaz.stream.{Step, Process}


object MergeX {

  /** Strategy to merge up and down stream processes **/
  type MergeXStrategy[W, I, O] = Process1[MergeSignal[W, I, O], MergeAction[W, O]]

  /** Reference indicating up or down-stream **/
  trait MergeRef
  trait UpRef extends MergeRef
  trait DownRef extends MergeRef
  trait DownRefO extends DownRef
  trait DownRefW extends DownRef

  /** Reference indicating action for mergeX to take **/
  sealed trait MergeAction[W, O]
  /** Request more from supplied upstream **/
  case class More[W, O](ref: UpRef) extends MergeAction[W, O]
  /** Write `O` value to downstream **/
  case class WriteO[W, O](so: Seq[O], ref: DownRefO) extends MergeAction[W, O]
  /** Write `W` value to downstream **/
  case class WriteW[W, O](sw: Seq[W], ref: DownRefW) extends MergeAction[W, O]
  /** Close the up/down stream **/
  case class Close[W, O](rsn: Throwable, ref: MergeRef) extends MergeAction[W, O]


  /**
   * Signal to be processed by [[scalaz.stream.merge.MergeX.MergeXStrategy]].
   * The `mx` has helpers to be used when constructing actions and it allows to check
   * state of merged up/downstream.
   */
  sealed trait MergeSignal[W, I, O] {
    val mx: MX[W, I, O]
  }

  /** sequence of `I` was received from upstream **/
  case class Receive[W, I, O](mx: MX[W, I, O], is: Seq[I], ref: UpRef) extends MergeSignal[W, I, O]
  /** downstream is ready to consume more `O` or `W` **/
  case class Ready[W, I, O](mx: MX[W, I, O], ref: DownRef) extends MergeSignal[W, I, O]
  /** downstream or upstream will start to be ready to get more `I` or consume `W`/`O` **/
  case class Open[W, I, O](mx: MX[W, I, O], ref: MergeRef) extends MergeSignal[W, I, O]
  /** downstream or upstream is done with given reason. **/
  case class Done[W, I, O](mx: MX[W, I, O], ref: MergeRef, rsn: Throwable) extends MergeSignal[W, I, O]
  /** source of upstream is done with given reason **/
  case class DoneUp[W, I, O](mx: MX[W, I, O], rsn: Throwable) extends MergeSignal[W, I, O]
  /** downstream has been forcefully closed with given reason **/
  case class DoneDown[W, I, O](mx: MX[W, I, O], rsn: Throwable) extends MergeSignal[W, I, O]


  /**
   * Helper for syntax and contains current sate of merged processes
   * @param up          All upstream processes
   * @param upReady     All upstream processes that wait for Ack ([[scalaz.stream.merge.MergeX.More]] to get next `I`
   * @param downW       All downstream processes that accept `W` values
   * @param downReadyW  All downstream processes that are ready to get next `W`
   * @param downO       All downstream processes that accept `O` values
   * @param downReadyO  All downstream processes that are ready to get next `O`
   * @param doneDown    When downstream processes are closed this is set with reason
   * @param doneUp      When upstream processes are closed this is set with reason
   */
  case class MX[W, I, O](
    up: Seq[UpRef]
    , upReady: Seq[UpRef]
    , downW: Seq[DownRefW]
    , downReadyW: Seq[DownRefW]
    , downO: Seq[DownRefO]
    , downReadyO: Seq[DownRefO]
    , doneDown: Option[Throwable]
    , doneUp: Option[Throwable]
    ) {

    /** Distributes seq of `O` to supplied references.
      * Returns tuple of remaining items that were not distributed and strategy with actions
      * **/
    def distributeO(so: Queue[O], ref: Seq[DownRefO]): (Queue[O], MergeXStrategy[W, I, O]) = {
      (so.drop(ref.size), emitSeq(so.zip(ref).map { case (o, r) => WriteO[W, O](List(o), r) }))
    }

    /** Distributes seq of `W` to supplied references.
      * Returns tuple of remaining items that were not distributed and strategy with actions
      * **/
    def distributeW(sw: Queue[W], ref: Seq[DownRefW]): (Queue[W], MergeXStrategy[W, I, O]) = {
      (sw.drop(ref.size), emitSeq(sw.zip(ref).map { case (w, r) => WriteW[W, O](List(w), r) }))
    }

    /** Broadcasts `W` value to all `W` downstream **/
    def broadcastW(w: W): MergeXStrategy[W, I, O] =
      broadcastAllW(List(w))

    /** Broadcasts sequence of `W` values to all `W` downstream **/
    def broadcastAllW(sw: Seq[W]): MergeXStrategy[W, I, O] =
      emitSeq(downW.map(WriteW[W, O](sw, _)))

    /** Broadcasts `O` value to all `O` downstream **/
    def broadcastO(o: O): MergeXStrategy[W, I, O] =
      broadcastAllO(List(o))

    /** Broadcasts sequence of `O` values to all `O` downstream **/
    def broadcastAllO(so: Seq[O]): MergeXStrategy[W, I, O] =
      emitSeq(downO.map(WriteO[W, O](so, _)))

    /** Broadcasts sequence of either `W` or `O` values to either downstreams on `W` or `O` side respectively **/
    def broadcastAllBoth(swo: Seq[W \/ O]): MergeXStrategy[W, I, O] =
      swo.foldLeft[MergeXStrategy[W, I, O]](halt) {
        case (p, \/-(o)) => p fby broadcastO(o)
        case (p, -\/(w)) => p fby broadcastW(w)
      }

    /** Write single `W` to supplied downstream **/
    def writeW(w: W, ref: DownRefW): MergeXStrategy[W, I, O] =
      writeAllW(List(w), ref)

    /** Write all `W` to supplied downstream **/
    def writeAllW(sw: Seq[W], ref: DownRefW): MergeXStrategy[W, I, O] =
      emit(WriteW(sw, ref))

    /** Write single `W` to supplied downstream **/
    def writeO(o: O, ref: DownRefO): MergeXStrategy[W, I, O] =
      writeAllO(List(o), ref)

    /** Write all `W` to supplied downstream **/
    def writeAllO(so: Seq[O], ref: DownRefO): MergeXStrategy[W, I, O] =
      emit(WriteO(so, ref))

    /** Signals more to upstream reference **/
    def more(ref: UpRef): MergeXStrategy[W, I, O] =
      emit(More(ref))

    /** Signals more to all upstream references that are ready **/
    def moreAll: MergeXStrategy[W, I, O] =
      emitSeq(upReady.map(More[W, O](_)))

    /** Closes supplied reference with given reason **/
    def close(ref: MergeRef, rsn: Throwable): MergeXStrategy[W, I, O] =
      emit(Close[W, O](rsn, ref))

    /** Closes all upstream  references **/
    def closeAllUp(rsn: Throwable): MergeXStrategy[W, I, O] =
      closeAll(up, rsn)

    /** Closes all upstream  references **/
    def closeAllDown(rsn: Throwable): MergeXStrategy[W, I, O] =
      closeAll(downO ++ downW, rsn)

    /** Closes all supplied references **/
    def closeAll(refs: Seq[MergeRef], rsn: Throwable) =
      emitSeq(refs.map(Close[W, O](rsn, _)))


    /////

    /** returns true when there are no active references in MX **/
    private[merge] def isClear = up.isEmpty && downO.isEmpty && downW.isEmpty
    override def toString: String =
      s"MX[up=$up,upReady=$upReady,downO=$downO,downReadyO=$downReadyO,downW=$downW,downReadyW=$downReadyW,doneDown=$doneDown,doneUp=$doneUp]"
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val ok = right(())


  def apply[W, I, O](strategy: MergeXStrategy[W, I, O], source: Process[Task, Process[Task, I]])
    (S: Strategy = Strategy.DefaultStrategy): MergeX[W, I, O] = {


    trait M
    //next step of source
    case class SourceStep(s: Step[Task, Process[Task, I]]) extends M

    //upstream source process is done
    case class UpStreamDone(ref: ProcessRef, rsn: Throwable) extends M
    //upstream source process is ready to Emit
    case class UpStreamEmit(ref: ProcessRef, si: Seq[I], t:Process[Task,I],c:Process[Task,I]) extends M
    
    //upstream process has seq of `I` to emit. Tail and cleanup is passed to build the current state
    case class UpEmit(ref: UpRefInstance, si: Seq[I]) extends M

    // `O`, `W` or `Both` get opened
    case class DownOpenO(ref: DownRefOInstanceImpl, cb: Throwable \/ Unit => Unit) extends M
    case class DownOpenW(ref: DownRefWInstanceImpl, cb: Throwable \/ Unit => Unit) extends M
    case class DownOpenBoth(ref: DownRefBothInstance, cb: Throwable \/ Unit => Unit) extends M

    // `O`, `W` or `Both` are ready to consume next value
    case class DownReadyO(ref: DownRefOInstanceImpl, cb: Throwable \/ Seq[O] => Unit) extends M
    case class DownReadyW(ref: DownRefWInstanceImpl, cb: Throwable \/ Seq[W] => Unit) extends M
    case class DownReadyBoth(ref: DownRefBothInstance, cb: Throwable \/ Seq[W \/ O] => Unit) extends M

    // `O`, `W` or `Both` are done
    case class DownDoneO(ref: DownRefOInstanceImpl, rsn: Throwable) extends M
    case class DownDoneW(ref: DownRefWInstanceImpl, rsn: Throwable) extends M
    case class DownDoneBoth(ref: DownRefBothInstance, rsn: Throwable) extends M

    //downstream is closed
    case class DownDone(rsn: Throwable, cb: Throwable \/ Unit => Unit) extends M




    sealed trait UpRefInstance extends UpRef {
      def next[B](actor: Actor[M])(implicit S: Strategy): Unit
      def close[B](actor: Actor[M], rsn: Throwable)(implicit S: Strategy): Unit
    }

    class UpstreamAsyncRef(val cb: Throwable \/ Unit => Unit) extends UpRefInstance {
      def next[B](actor: Actor[M])(implicit S: Strategy): Unit = S(cb(ok))
      def close[B](actor: Actor[M], rsn: Throwable)(implicit S: Strategy): Unit = S(cb(left(rsn)))
    }


    // Keeps state of upstream source
    sealed trait UpSourceState[A]
    // signals upstream source is ready to be run or cleaned
    case class UpSourceReady[A](cont: Process[Task, A], cleanup: Process[Task, A]) extends UpSourceState[A]
    // signals upstream source is running, and may be interrupted
    case class UpSourceRunning[A](interrupt: () => Unit) extends UpSourceState[A]
    // signals upstream source is done
    case class UpSourceDone[A](rsn: Throwable) extends UpSourceState[A]


    // Reference for processes provided by upstream source.
    // state keeps state of the upstream and is guaranteed to be not accessed
    // from multiple threads concurrently
    class ProcessRef(@volatile var state: UpSourceState[I]) extends UpRefInstance {
      private val self = this

      def close[B](actor: Actor[M], rsn: Throwable)(implicit S: Strategy): Unit = state match {
        case UpSourceReady(t, c) =>
          S(WyeActor.runStepAsyncInterruptibly[I](c.causedBy(rsn)) {
            _ => actor ! UpStreamDone(self, rsn)
          })
          state = UpSourceDone(rsn)

        case UpSourceRunning(interrupt) =>
          S(interrupt())
          state = UpSourceDone(rsn)


        case UpSourceDone(_) => //no-op
      }

      def next[B](actor: Actor[M])(implicit S: Strategy): Unit = {
        state match {
          case UpSourceReady(t, c) =>
            state = UpSourceRunning[I](WyeActor.runStepAsyncInterruptibly(t) {
              step =>
                step match {
                  case Step(\/-(si), t, c)  => actor ! UpStreamEmit(self, si, t, c)
                  case Step(-\/(rsn), _, _) => actor ! UpStreamDone(self, rsn)
                }
            })
          case _                   => //no-op
        }
      }
      
      def ready(t:Process[Task,I],c:Process[Task,I]) : Unit = {
        state = UpSourceReady(t,c)
      }
    }

    trait DownRefInstance[A] extends DownRef {
      def push(xb: Seq[A])(implicit S: Strategy): Unit
      def close(rsn: Throwable)(implicit S: Strategy): Unit
    }


    // Reference for downstream. Internal mutable vars are protected and set only by mergeX actor
    trait DownRefInstanceImpl[A] extends DownRefInstance[A] {
      // State of reference, may be queueing (left) or waiting to be completed (right)
      @volatile var state: Vector[A] \/ ((Throwable \/ Seq[A]) => Unit)
      // When set, indicates reference is terminated and shall not receive more `B`
      // except these in queue already.
      @volatile var done: Option[Throwable] = None

      // signals next `B` is ready. This may complete the step of reference
      // or enqueues and waits for callback to be ready
      def push(xb: Seq[A])(implicit S: Strategy): Unit =
        done match {
          case None =>
            state = state.fold(
              q => left(q ++ xb)
              , cb => {
                S(cb(right(xb)))
                left(Vector())
              }
            )

          case Some(rsn) => ()
        }

      // reference is ready to get more `B`
      // this either supplies given callback or registers
      // callback to be calles on `push` or `done`
      def ready(cb: (Throwable \/ Seq[A]) => Unit)(implicit S: Strategy) = {
        state = state.fold(
          q =>
            if (q.isEmpty) {
              done match {
                case None      => right(cb)
                case Some(rsn) =>
                  S(cb(left(rsn)))
                  state
              }
            } else {
              S(cb(right(q)))
              left(Vector())
            }

          , _ => {
            // this is invalid state cannot have more than one callback
            // new callback is failed immediatelly
            S(cb(left(new Exception("Only one callback allowed"))))
            state
          }
        )
      }

      // returns true if callback is registered
      def withCallback = state.isRight

      // Signals done, this ref would not enqueue more elements than in queue from now on
      // that means on next `get` it will get all remaining `A` queued, and then terminate
      // or will just terminate now in case callback is registered.
      def close(rsn: Throwable)(implicit S: Strategy) = {
        done match {
          case Some(_) => ()
          case None    => done = Some(rsn)
        }
        state = state.fold(
          q => state
          , cb => {
            S(cb(left(rsn)))
            left(Vector())
          }

        )
      }

    }

    trait DownRefOInstance extends DownRefInstance[O] with DownRefO
    trait DownRefWInstance extends DownRefInstance[W] with DownRefW

    class DownRefOInstanceImpl(
      @volatile var state: \/[Vector[O], (\/[Throwable, Seq[O]]) => Unit] = left(Vector())
      ) extends DownRefInstanceImpl[O] with DownRefOInstance
    
  

    class DownRefWInstanceImpl(
      @volatile var state: \/[Vector[W], (\/[Throwable, Seq[W]]) => Unit] = left(Vector())
      ) extends DownRefInstanceImpl[W] with DownRefWInstance


    class DownRefBothInstance(
      @volatile var state: \/[Vector[W \/ O], (\/[Throwable, Seq[W \/ O]]) => Unit] = left(Vector())
      ) extends DownRefInstanceImpl[W \/ O] {

      val self = this

      @volatile var doneO: Option[Throwable] = None
      @volatile var doneW: Option[Throwable] = None

      val oi = new DownRefOInstance {
        def push(xb: Seq[O])(implicit S: Strategy): Unit =
          if (doneO.isEmpty) self.push(xb.map(right))

        def close(rsn: Throwable)(implicit S: Strategy): Unit = {
          doneO = Some(rsn)
          if (doneW.isDefined) self.close(rsn)
        }
      }
      val wi  = new DownRefWInstance {
        def push(xb: Seq[W])(implicit S: Strategy): Unit =
          if (doneW.isEmpty) self.push(xb.map(left))

        def close(rsn: Throwable)(implicit S: Strategy): Unit = {
          doneW = Some(rsn)
          if (doneO.isDefined) self.close(rsn)
        }
      }
    }




    ///////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////////////////

    var xstate: MergeXStrategy[W, I, O] = strategy

    var mx: MX[W, I, O] = MX(Vector(), Vector(), Vector(), Vector(), Vector(), Vector(), None, None)

    //when set, indicates source of upstream was run and is in some state
    var sourceState: Option[UpSourceState[Process[Task, I]]] = None

    var downDoneSignals: Vector[Throwable \/ Unit => Unit] = Vector()

    //decalred here because of forward-referencing below
    var actor: Actor[M] = null

    //starts source if not yet started
    def startSource(actor:Actor[M]) : Unit =
      sourceState match {
        case None =>  nextSource(source,actor)
        case Some(_) => //no-op
      }

    // runs next source step
    def nextSource(p: Process[Task, Process[Task, I]], actor: Actor[M]) : Unit =
      sourceState = Some(UpSourceRunning(WyeActor.runStepAsyncInterruptibly(p) { s => actor ! SourceStep(s) }))


    //cleans next source step
    def cleanSource(rsn: Throwable, c: Process[Task, Process[Task, I]], a: Actor[M]): Unit = {
      sourceState = Some(UpSourceRunning(() => ())) //set to noop so clean won`t get interrupted
      WyeActor.runStepAsyncInterruptibly(c.drain) { s => actor ! SourceStep(s) }
    }


    /** Signals that all has been cleared, but only if mx is clear **/
    def signalAllClearWhenDone: Unit = {
      if (mx.isClear && sourceState.collect({case UpSourceDone(rsn) => rsn}).isDefined) {
        downDoneSignals.foreach(cb=>S(cb(ok)))
        downDoneSignals = Vector()
      }
    }

    def process(signal: MergeSignal[W, I, O]): Unit = {
      def run(acts: Seq[MergeAction[W, O]]): Unit = {
        acts.foreach {
          case More(ref: UpRefInstance)          =>
            mx = mx.copy(upReady = mx.upReady.filterNot(_ == ref))
            ref.next(actor)
          case More(_) => //bacuse of pattern match warning
          case WriteO(so, ref: DownRefOInstance) =>
            mx = mx.copy(downReadyO = mx.downReadyO.filterNot(_ == ref))
            ref.push(so)
          case WriteW(sw, ref: DownRefWInstance) =>
            mx = mx.copy(downReadyW = mx.downReadyW.filterNot(_ == ref))
            ref.push(sw)
          case Close(rsn, ref: UpRefInstance)    =>
            mx = mx.copy(
              up = mx.up.filterNot(_ == ref)
              , upReady = mx.upReady.filterNot(_ == ref)
            )
            ref.close(actor, rsn)
          case Close(rsn, ref: DownRefOInstance) =>
            mx = mx.copy(
              downO = mx.downO.filterNot(_ == ref)
              , downReadyO = mx.downReadyO.filterNot(_ == ref)
            )
            ref.close(rsn)
          case Close(rsn, ref: DownRefWInstance) =>
            mx = mx.copy(
              downW = mx.downW.filterNot(_ == ref)
              , downReadyW = mx.downReadyW.filterNot(_ == ref)
            )
            ref.close(rsn)
        }
      }

      if (!xstate.isHalt) {
        xstate.feed1(signal).unemit match {
          case (acts, hlt@Halt(rsn)) =>
            run(acts)
            mx.up.foreach { case ref: UpRefInstance => ref.close(actor, rsn) }
            mx.downO.foreach { case ref: DownRefOInstance => ref.close(rsn) }
            mx.downW.foreach { case ref: DownRefWInstance => ref.close(rsn) }
            mx = mx.copy(upReady = Nil, downReadyO = Nil, downReadyW = Nil) //we keep the references except `ready` to callback on eventual downstreamClose signal once all are done.
            sourceState match {
              case Some(UpSourceReady(t, c))        => cleanSource(rsn,c, actor)
              case Some(UpSourceRunning(interrupt)) => S(interrupt())
              case None => sourceState = Some(UpSourceDone(End))
              case _ => //no-op
            }
            signalAllClearWhenDone
            xstate = hlt
          case (acts, nx)            =>
            run(acts)
            xstate = nx
        }
      }
    }


    actor = Actor[M] {
      msg =>
        xstate match {
          case Halt(rsn) =>
            msg match {
              case SourceStep(step) => step match {
                case Step(\/-(ups), t, c)        =>
                  mx = mx.copy(doneUp = Some(rsn))
                  cleanSource(rsn, c, actor)
                case Step(-\/(rsn0), _, Halt(_)) =>
                  sourceState = Some(UpSourceDone(rsn))
                  mx = mx.copy(doneUp = Some(rsn0))
                case Step(-\/(_), _, c)          =>
                  mx = mx.copy(doneUp = Some(rsn))
                  cleanSource(rsn, c, actor)
              }

              case UpEmit(ref, _)   => ref.close(actor, rsn)
              case UpStreamEmit(ref,_,t,c) => ref.ready(t,c); ref.close(actor,rsn)
              case UpStreamDone(ref, rsn) => mx = mx.copy(up = mx.up.filterNot(_ == ref))
              case DownOpenO(ref, cb)     => S(cb(left(rsn)))
              case DownOpenW(ref, cb)     => S(cb(left(rsn)))
              case DownOpenBoth(ref, cb)  => S(cb(left(rsn)))
              case DownReadyO(ref, cb)    => ref.close(rsn); ref.ready(cb)
              case DownReadyW(ref, cb)    => ref.close(rsn); ref.ready(cb)
              case DownReadyBoth(ref, cb) => ref.close(rsn); ref.ready(cb)
              case DownDoneO(ref, rsn)    => mx = mx.copy(downO = mx.downO.filterNot(_ == ref))
              case DownDoneW(ref, rsn)    => mx = mx.copy(downW = mx.downW.filterNot(_ == ref))
              case DownDoneBoth(ref, rsn) =>
                mx = mx.copy(
                  downO = mx.downO.filterNot(_ == ref.oi)
                  , downW = mx.downW.filterNot(_ == ref.wi)
                )
              case DownDone(rsn, cb)      =>
                if (mx.isClear) {
                  S(cb(ok))
                } else {
                  downDoneSignals = downDoneSignals :+ cb
                }
            }
            signalAllClearWhenDone


          case _ => msg match {

            case SourceStep(step) => step match {
              case Step(\/-(ups), t, c) =>
                val newUps = ups.map(t => new ProcessRef(UpSourceReady(t, halt)))
                mx = mx.copy(up = mx.up ++ newUps, upReady = mx.upReady ++ newUps)
                newUps.foreach(ref => process(Open(mx, ref)))
                nextSource(t, actor)

              case Step(-\/(rsn), _, Halt(_)) =>
                sourceState = Some(UpSourceDone(rsn))
                mx = mx.copy(doneDown = Some(rsn))
                process(DoneUp(mx, rsn))

              case Step(-\/(rsn), _, c) =>
                cleanSource(rsn, c, actor)
                mx = mx.copy(doneDown = Some(rsn))
                process(DoneUp(mx, rsn))
            }

            case UpStreamEmit(ref,is,t,c) =>
              ref.ready(t,c)
              mx = mx.copy(upReady = mx.upReady :+ ref)
              process(Receive(mx, is, ref))

            case UpEmit(ref, is) =>
              mx = mx.copy(upReady = mx.upReady :+ ref)
              process(Receive(mx, is, ref))

            case UpStreamDone(ref, rsn) =>
              mx = mx.copy(up = mx.up.filterNot(_ == ref), upReady = mx.upReady.filterNot(_ == ref))
              process(Done(mx, ref, rsn))

            case DownOpenO(ref, cb) =>
              startSource(actor)
              mx = mx.copy(downO = mx.downO :+ ref)
              process(Open(mx, ref))
              S(cb(ok))

            case DownOpenW(ref, cb) =>
              mx = mx.copy(downW = mx.downW :+ ref)
              process(Open(mx, ref))
              S(cb(ok))

            case DownOpenBoth(ref, cb) =>
              mx = mx.copy(downW = mx.downW :+ ref.wi, downO = mx.downO :+ ref.oi)
              process(Open(mx, ref.wi))
              process(Open(mx, ref.oi))
              S(cb(ok))

            case DownReadyO(ref, cb) =>
              ref.ready(cb)
              if (ref.withCallback) {
                mx = mx.copy(downReadyO = mx.downReadyO :+ ref)
                process(Ready(mx, ref))
              }
            case DownReadyW(ref, cb) =>
              ref.ready(cb)
              if (ref.withCallback) {
                mx = mx.copy(downReadyW = mx.downReadyW :+ ref)
                process(Ready(mx, ref))
              }

            case DownReadyBoth(ref, cb) =>
              ref.ready(cb)
              if (ref.withCallback) {
                mx = mx.copy(downReadyW = mx.downReadyW :+ ref.wi)
                process(Ready(mx, ref.wi))
                mx = mx.copy(downReadyO = mx.downReadyO :+ ref.oi)
                process(Ready(mx, ref.oi))
              }

            case DownDoneO(ref, rsn) =>
              mx = mx.copy(
                downO = mx.downO.filterNot(_ == ref)
                , downReadyO = mx.downReadyO.filterNot(_ == ref)
              )
              process(Done(mx, ref, rsn))

            case DownDoneW(ref, rsn) =>
              mx = mx.copy(
                downW = mx.downW.filterNot(_ == ref)
                , downReadyW = mx.downReadyW.filterNot(_ == ref)
              )
              process(Done(mx, ref, rsn))

            case DownDoneBoth(ref, rsn) =>
              mx = mx.copy(
                downO = mx.downO.filterNot(_ == ref.oi)
                , downReadyO = mx.downReadyO.filterNot(_ == ref.oi)
                , downW = mx.downW.filterNot(_ == ref.wi)
                , downReadyW = mx.downReadyW.filterNot(_ == ref.wi)
              )
              process(Done(mx, ref.wi, rsn))
              process(Done(mx, ref.oi, rsn))

            case DownDone(rsn, cb) =>
              if (downDoneSignals.isEmpty) {
                mx = mx.copy(doneDown = Some(rsn))
                downDoneSignals = Vector(cb)
                process(DoneDown(mx, rsn))
              } else {
                downDoneSignals = downDoneSignals :+ cb
              }

          }

        }
    }


    new MergeX[W, I, O] {
      def receiveAll(si: Seq[I]): Task[Unit] =
        Task.async { cb => actor ! UpEmit(new UpstreamAsyncRef(cb), si) }
      def upstreamSink: Process.Sink[Task, I] =
        Process.constant(receiveOne _)

      //todo: revisit this once cleanup will get reason passed
      def downstream_[R, A](
        getRef: => R
        , open: (R, Throwable \/ Unit => Unit) => M
        , ready: (R, Throwable \/ Seq[A] => Unit) => M
        , close: (R, Throwable) => M): Process[Task, A] = {

        def done(ref: R, rsn: Throwable) =
          eval_(Task.delay(actor ! close(ref, rsn)))

        await(Task.delay(getRef))(
          ref => {
            await(Task.async[Unit](cb => actor ! open(ref, cb)))(
              _ => repeatEval(Task.async[Seq[A]](cb => actor ! ready(ref, cb))).flatMap(emitAll) onComplete done(ref, End)
              , done(ref, End)
              , done(ref, End)
            )
          }
          , halt
          , halt
        )
      }

      def downstreamO: Process[Task, O] =
        downstream_[DownRefOInstanceImpl, O](new DownRefOInstanceImpl(), DownOpenO, DownReadyO, DownDoneO)

      def downstreamW: Process[Task, W] =
        downstream_[DownRefWInstanceImpl, W](new DownRefWInstanceImpl(), DownOpenW, DownReadyW, DownDoneW)

      def downstreamBoth: Process.Writer[Task, W, O] =
        downstream_[DownRefBothInstance, W \/ O](new DownRefBothInstance(), DownOpenBoth, DownReadyBoth, DownDoneBoth)

      def downstreamClose(e: Throwable): Task[Unit] =
        Task.async(cb => actor ! DownDone(e, cb))
    }
  }


}


/**
 * Low-level asynchronous primitive that allows asynchronously and
 * non-deterministically to merge multiple processes and external effects
 * together to create source in form of other processes.
 *
 * Please consider using its variants from [[scalaz.stream.async]] or [[scalaz.stream.merge]] package
 * before using this directly.
 *
 * Merging process is controlled by MergeXStrategy which takes form of
 * Process1, that on input side has [[scalaz.stream.merge.MergeX.MergeSignal]]
 * and on output side [[scalaz.stream.merge.MergeX.MergeAction]].
 * Please see [[scalaz.stream.merge.MergeXStrategies]] for more details.
 *
 * Processes that push to MergeX are called `upstream` processes and processes
 * that take from the the merge are called `downstream` processes.
 *
 * The mergeX starts once at least one `downstream` Process is run, and will
 * stop when `MergeXStrategy` stops.
 *
 * At any time user of MergeX can call `downstreamClose` method, that will cause
 * [[scalaz.stream.merge.MergeX.DoneDown]] to be dispatched to `MergeXStrategy`.
 *
 * Similarly, when Process providing `upstream` processes (the `source`) will terminate, this
 * MergeX will signal to MergeStrategy [[scalaz.stream.merge.MergeX.DoneUp]] message to indicate that
 * no more `Upstream` channels will provide values in the merge except those
 * providing already.
 *
 * Note there are two kind of `upstream` references :
 *
 * - `from source`, created by running processes provided by source and
 * - `volatile` that are provided by `receiveOne` and `upstreamSink` methods. These are not
 * consulted when [[scalaz.stream.merge.MergeX.DoneUp]] is dispatched to MergeX strategy.
 *
 *
 */
trait MergeX[+W, -I, +O] {

  /**
   * Creates task, that when evaluated will make mergeX to receive Seq of `I`.
   * This will complete _after_ mergeX confirms that more `I` are needed
   * by MergeXStrategy emitting [[scalaz.stream.actor.MergeStrategy.More]].
   *
   * Please note this creates `volatile` upstream reference that has no notion of
   * being `first` or `done` like with references from upstream source.
   *
   * @param si
   * @return
   */
  def receiveAll(si: Seq[I]): Task[Unit]

  /** Helper for receiveing only one `I`. See `receiveAll` **/
  def receiveOne(i: I): Task[Unit] = receiveAll(List(i))

  /**
   * Creates sink that feeds `I` into mergeX.
   *
   * This sink can be attached to any Source of `I` and then resulting process may be run to feed this merge with `I`
   *
   * @return
   */
  def upstreamSink: Sink[Task, I]


  /**
   * Creates one downstream process that may be used to drain `O` from this mergeX.
   * Please not once first `downstream` is run, the mergeX will start to run all of its
   * upstream processes.
   *
   * Please note this will result in following messages to be emitted to `MergeXStrategy` :
   *
   * - [[scalaz.stream.merge.MergeX.Open]] on first attempt to read from MergeX
   * - [[scalaz.stream.merge.MergeX.Ready]] repeatedly on subsequent reads
   * - [[scalaz.stream.merge.MergeX.Done]] this stream terminates with supplied reason for termination
   *
   */
  def downstreamO: Process[Task, O]

  /** Like `downstreamO` but just draining `W` values **/
  def downstreamW: Process[Task, W]

  /** Drains both `W` and `O` values from this merge **/
  def downstreamBoth: Writer[Task, W, O]

  /**
   * Causes orderly termination of mergeX. This causes MergeStrategy to
   * receive [[scalaz.stream.actor.MergeStrategy.DownstreamClosed]] signal.
   * Based on MergeStrategy implementation this may or may not terminate downstream processes immediatelly.
   *
   * However please not this will complete after _all_ downstream processes have terminated.
   * Multiple invocation of this task will have no-op but will complete after the first task completes.
   *
   * @param e reason for termination. Pass `End` to indicate resource exhausted shutdown.
   */
  def downstreamClose(e: Throwable): Task[Unit]


}
