package scalaz.stream.merge

import scala.collection.immutable.Queue
import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz.stream.Process._


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
  case class Close[W, O](ref: MergeRef, rsn: Throwable) extends MergeAction[W, O]


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
   */
  case class MX[W, I, O](
    up: Seq[UpRef]
    , upReady: Seq[UpRef]
    , downW: Seq[DownRefW]
    , downReadyW: Seq[DownRefW]
    , downO: Seq[DownRefO]
    , downReadyO: Seq[DownRefO]
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
      emit(Close[W, O](ref, rsn))

    /** Closes all upstream  references **/
    def closeAllUp(rsn: Throwable): MergeXStrategy[W, I, O] =
      closeAll(up, rsn)

    /** Closes all upstream  references **/
    def closeAllDown(rsn: Throwable): MergeXStrategy[W, I, O] =
      closeAll(downO ++ downW, rsn)

    /** Closes all supplied references **/
    def closeAll(refs: Seq[MergeRef], rsn: Throwable) =
      emitSeq(refs.map(Close[W, O](_, rsn)))


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
