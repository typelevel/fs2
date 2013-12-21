package scalaz.stream

import Process._
import processes._
import scalaz.\/._
import scalaz._
import scalaz.concurrent.{Strategy, Task}

/**
 * Exchange represents interconnection between two systems.
 * So called remote is resource from which program receives messages of type `I`
 * and can send to it messages of type `O`.
 *
 * Typically this can be sort of connection to external system,
 * like for example tcp connection to internet server.
 *
 * Exchange allows combining this pattern with Processes and
 * allows to use different combinators to specify the Exchange behaviour.
 *
 * Exchange is currently specialized to [[scalaz.concurrent.Task]]
 *
 * @tparam I
 * @tparam O
 */
trait Exchange[I,O] {

  self =>

  /**
   * Provides process, that when run will supply received (read) values `I` from external system
   *@return
   */
  def read: Process[Task, I]

  /**
   * Provides sink, that sends(writes) the `O` values to external system
   * @return
   */
  def write: Sink[Task, O]


  /**
   * uses provided function `f` to be applied on any `I` received
   */
  def map[A](f:I=>A) :  Exchange[A,O] = new Exchange[A,O] {
    def read: Process[Task, A] = self.read map f
    def write: scalaz.stream.Sink[Task, O] = self.write
  }

  /**
   * applies provided function to any `A` that has to be written to provide an `O`
   */
  def mapW[A](f:A=>O) : Exchange[I,A] = new Exchange[I,A] {
    def read: Process[Task, I] = self.read
    def write: scalaz.stream.Sink[Task, A] = self.write contramap f
  }


  /**
   * Creates exchange that `pipe` read `I` values through supplied p1.
   * @param p1   Process1 to be used when reading values
   */
  def pipe[A](p1: Process1[I, A]): Exchange[A, O] = new Exchange[A, O] {
    def read: Process[Task, A] = self.read.pipe(p1)
    def write: scalaz.stream.Sink[Task, O] = self.write
  }


  /**
   * Creates new exchange, that pipes all values to be sent through supplied `p1`
   */
  def pipeW[A](p1: Process1[A, O]): Exchange[I, A] = new Exchange[I, A] {
    def read = self.read
    def write = self.write.pipeIn(p1)
  }

  /**
   * Creates new exchange, that will pipe read values through supplied `r` and write values through `w`
   * @param r  Process1 to use on all read values
   * @param w  Process1 to use on all written values
   */
  def pipeBoth[A, B](r: Process1[I, A], w: Process1[B, O]): Exchange[A, B] =
    self.pipe(r).pipeW(w)


  /**
   * Creates Exchange that runs read `I` through supplied effect channel.
   * @param ch Channel producing process of `A` for each `I` received
   * @tparam A
   * @return
   */
  def through[A](ch:Channel[Task,I,Process[Task,A]]): Exchange[A,O] = new Exchange[A,O] {
    def read =  (self.read through ch) flatMap identity
    def write = self.write
  }


  /**
   * Reads received `I` and consults w to eventually produce `O` that has to be written to external system or `A` that
   * will be emitted as result when running this process.
   * @param w  Writer that processes received `I` and either echoes `A` or writes `O` to external system
   *
   */
  def throughW[A](w: Process1W[O, I, A]): Process[Task, A] =
    read.pipe(w).flatMap {
      case -\/(o) => eval_((emit(o).toSource to self.write).run)
      case \/-(a) => emit(a)
    }


  /**
   * Runs the exchange through supplied wye writer, merging with supplied stream. If Wye writer supplies `O` it is sent to
   * external system. If WyeW supplies `B` it is sent to resulting process
   *
   * Please note that `O` are concurrently written to external system once the WyeW `writes` them. This can lead to
   * unbounded heap usage, if the external system is very slow on read. If you want to control flow and limit the
   * written values in the queue, rather use `readAndWriteFlow` with flow-control capabilities.
   *
   *
   * @param p     Process to be consulted in wye
   * @param y     wye that merges `I` received from server or the `A`
   * @param S     Wye strategy
   */
  def readAndWrite[A, B, C](p: Process[Task, A])(y: WyeW[O, I, A, B])(implicit S: Strategy = Strategy.DefaultStrategy): Process[Task, B] = {
    readAndWriteFlow(p)(y.attachL(collect { case \/-(i) => i }))(S)
  }

  /**
   * Variant of readAndWrite that allows WyeW to control flow of written values, to eventually compensate for slow
   * reading external systems.
   *
   * After each value written to external system wyeW is notified on right side with number of `O` values waiting in queue.
   * If wye decides that number of queued `O` is too high, it may start to switch to read only from left and
   * wait on left side for signal (queue size of `O`) to start reading from both sides again.
   *
   *
   * @param p     Process to be consulted in wye for writing the data to external system
   * @param y     wye that merges `I` received from server or the `A`, This wye allows also to flow-control writes by
   *              examining received `Int` on left side
   * @param S     Wye strategy
   */
  def readAndWriteFlow[A, B, C](p: Process[Task, A])(y: WyeW[O, Int \/ I, A, B])(implicit S: Strategy = Strategy.DefaultStrategy): Process[Task, B] = {
    val (wq, wSource) = async.queue[O]
    val queueSize = async.signal[Int]

    val increment: Task[Unit] = queueSize.compareAndSet {
      case Some(i) => Some(i + 1)
      case None    => Some(1)
    }.map(_ => ())

    val decrement: Task[Unit] = queueSize.compareAndSet {
      case Some(i) => Some(i - 1)
      case None    => Some(0) // should never be the case
    }.map(_ => ())

    def setup: Process[Task, Nothing] =
      eval_(queueSize.set(0))

    def runWriteFromQueue: Process[Task, Nothing] =
      eval_(Task.delay(S((wSource.flatMap(o => eval_(increment) fby emit(o)) to self.write).flatMap(_ => eval_(decrement)).run.runAsync(_ => ()))))

    def receive: Process[Task, B] =
      (queueSize.discrete either self.read).wye(p)(y)(S).flatMap {
        case -\/(o) =>
          println("!!!!!TO SERVER")
          eval_ { (emit(o).toSource to self.write).run.map(r => println("Send done", r)) }
        case \/-(b) =>
          println("GOOSH, RECEIVED")
          emit(b)
      }

    def cleanup: Process[Task, Nothing] =
      eval_(queueSize.close) fby
        eval_(Task.delay(wq.close))


    setup fby runWriteFromQueue fby receive onComplete cleanup

  }


}
