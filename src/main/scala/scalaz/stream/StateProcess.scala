package scalaz.stream

import scalaz.\/
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process._
import scalaz.stream.async.immutable.Signal


/**
 * Like Process, but unlike it, it has internal state machine defined by supplied Writer1[S,A,B]
 *
 * Additionally this process can be fed by one or more sources, that can be other Process[Task,A].
 *
 * When this StateProcess is run, The source is run as well.
 *
 * This primitive allows to create complex network of distributed processes that exchange messages.
 *
 *
 */
trait StateProcess[S, A, B] {


  /**
   * Subscribes consumer to read state of this process wih eventual messages `B`.
   * Subscribed source will terminate once the StateProcess terminates
   */
  def subscribe: Process[Task, S \/ B]

  /**
   * Creates signal of state `S` of this StateProcess
   * @return
   */
  def signal: Signal[S]


  /**
   * Creates new StateProcess that is `piped` to this StateProcess via supplied `p1`.
   * If p1 terminates newly created StateProcess is terminated as well.
   * If `this` StateProcess is terminated, the resulting StateProcess is terminated as well.
   */
  def pipe[S2, C](w: Writer1[S2, S \/ B, C]): StateProcess[S2, B, C]

  /**
   * Creates new StateProcess, that is linked with `this` StateProcess in form of supplied wye.
   * If the wye terminates the new StateProcess terminates as well. If `this` StateProcess terminates,
   * the new StateProcess terminates as well.
   */
  def wye[S2, C, D](y: WyeW[S2, S \/ B, C, D])(p: Process[Task, C]): StateProcess[S2, B, D]

  /**
   * Creates new StateProcess that is `connected` to this StateProcess via supplied `w1` and `w2`.
   * If p1 terminates newly created StateProcess is closed.
   * If `this` StateProcess is closed, the new  StateProcess is closed as well.
   * If p2 or new State Process terminates, this StateProcess is  not terminated.
   * Additionally if any message is fed to newly created StateProcess
   * (including ones transformed by `p1` from this StateProcess),
   * they are processed by p2 and published to `this` StateProcess.
   * This allows for bidirectional communication between State Processes.
   */
  def connect[S2, C](wc: Writer1[S2, S \/ B, C], wa: Writer1[S, S2 \/ C, A]): StateProcess[S2, B, C]

  /**
   * Creates new StateProcess that is `connected` to this StateProcess via supplied `ye` and `ya`.
   * If ye terminates newly created StateProcess is closed.
   * If `this` StateProcess is closed, the new StateProcess is closed as well.
   * If ye terminates, this StateProcess is not terminated.
   * Additionally if any message is fed to newly created StateProcess
   * (including ones transformed by `ye` from this StateProcess and merged from s1),
   * they are processed by yd and published to `this` StateProcess. sd is consulted by yd and transformed messages
   * are fed to `this` StateProcess
   */
  def wyeConnect[S2, C, D, E](ye: WyeW[S2, S \/ B, C, E], sc: Process[Task, C])(ya: WyeW[S2, S2 \/ E, D, A], sd: Process[Task, D])


  type Connection[S,S2,A,B,C] = (Writer1[S2, S \/ B, C], Writer1[S, S2 \/ C, A])

  type WyeConnection[S,S2,A,B,C,D,E] = (WyeW[S2, S \/ B, C, E], Process[Task, C],  WyeW[S2, S2 \/ E, D, A], Process[Task, D])

  /**
   * Dynamically creates connected StateProcesses as defined by `d`. Once `d` emits new pair of `K` and `Connection`
   * the connected StateProcess is created as in case of `connect`.
   *
   * This allows to create dynamic patterns such as master-worker where master spawns workers and they perform they work
   * that is consumed by `master` once the `worker` stops its job.
   *
   */
  def dynamic[S2, K, C](d: Process1[S \/ B, (K,Connection[S,S2,A,B,C])]): StateProcess[Map[K,StateProcess[S2,B,C]], B, StateProcess[S2,B,C]]


  /** `wye` alternative of `dynamic` **/
  def dynamicWye[S2,K, C, D, E](d:Process1[S \/ B, (K,Connection[S,S2,A,B,C])])

}

trait RunnableStateProcess[S, A, B] extends StateProcess[S, A, B] {

  /**
   * Runs the state process.
   * Supplied strategy is used to run connected and piped state processes
   */
  def run(S: Strategy = Strategy.DefaultStrategy): Writer[Task, S, B]
}

object StateProcess {

  /** Creates StateProcess from supplied writer and stream of sources.
    * StateProcess will terminate once source or writer terminates
    * Sources are run in parallel by strategy supplied by `run` method
    */
  def from[S, A, B](w: Writer1[S, A, B], s: Process[Task, A]): RunnableStateProcess[S, A, B] = ???

  /** Creates StateProcess from supplied writer and stream of sources.
    * StateProcess will terminate once all sources sources or writer terminates.
    * If all sources terminated, but the `s` is not terminated the StateProcess will not terminate
    * Sources are run in parallel by strategy supplied at `run` method
    */
  def fromN[S, A, B](w: Writer1[S, A, B], s: Process[Task, Process[Task, A]]): RunnableStateProcess[S, A, B] = ???

  def dynamic[S, A, B](w: Writer1[S, A, B], s: Process[Task, A]): RunnableStateProcess[S, A, B] = ???

}
