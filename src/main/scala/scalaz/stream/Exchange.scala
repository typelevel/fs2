package scalaz.stream

import Process._
import processes._
import scalaz._
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.ReceiveY.{HaltR, HaltL, ReceiveR, ReceiveL}

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
 * @tparam I  values read from remote system
 * @tparam W  values written to remote system
 */
trait Exchange[I,W] {

  self =>

  /**
   * Provides process, that when run will supply received (read) values `I` from external system
   *@return
   */
  def read: Process[Task, I]

  /**
   * Provides sink, that sends(writes) the `W` values to external system
   * @return
   */
  def write: Sink[Task, W]


  //alphabetical order from now on

  /**
   * uses provided function `f` to be applied on any `I` received
   */
  def mapO[I2](f: I => I2): Exchange[I2, W] = new Exchange[I2, W] {
    def read: Process[Task, I2] = self.read map f
    def write: scalaz.stream.Sink[Task, W] = self.write
  }

  /**
   * applies provided function to any `W2` that has to be written to provide an `W`
   */
  def mapW[W2](f:W2=>W) : Exchange[I,W2] = new Exchange[I,W2] {
    def read: Process[Task, I] = self.read
    def write: scalaz.stream.Sink[Task, W2] = self.write contramap f
  }


  /**
   * Creates exchange that `pipe` read `I` values through supplied p1.
   * @param p1   Process1 to be used when reading values
   */
  def pipeO[I2](p1: Process1[I, I2]): Exchange[I2, W] = new Exchange[I2, W] {
    def read: Process[Task, I2] = self.read.pipe(p1)
    def write: scalaz.stream.Sink[Task, W] = self.write
  }


  /**
   * Creates new exchange, that pipes all values to be sent through supplied `p1`
   */
  def pipeW[W2](p1: Process1[W2, W]): Exchange[I, W2] = new Exchange[I, W2] {
    def read = self.read
    def write = self.write.pipeIn(p1)
  }

  /**
   * Creates new exchange, that will pipe read values through supplied `r` and write values through `w`
   * @param r  Process1 to use on all read values
   * @param w  Process1 to use on all written values
   */
  def pipeBoth[I2, W2](r: Process1[I, I2], w: Process1[W2, W]): Exchange[I2, W2] =
    self.pipeO(r).pipeW(w)


  /**
   * Runs  supplied Process of `W` values by sending them to remote system.
   * Any replies from remote system are received as `I` values of the resulting process.
   * @param p
   * @return
   */
  def run(p:Process[Task,W]):Process[Task,I] =
    (self.read merge (p to self.write).drain)

  /**
   * Runs this exchange in receive-only mode.
   *
   * Use when you want to sent to other party `W` only as result of receiving `I`. It is useful with
   * other combinator such as `wye`, `readThrough` where they will produce a `W` as a result of receiving a `I`.
   *
   * @return
   */
  def runReceive : Process[Task,I] = run(halt)


  /**
   * Creates Exchange that runs read `I` through supplied effect channel.
   * @param ch Channel producing process of `I2` for each `I` received
   */
  def through[I2](ch:Channel[Task,I,Process[Task,I2]]): Exchange[I2,W] = new Exchange[I2,W] {
    def read =  (self.read through ch) flatMap identity
    def write = self.write
  }

  /**
   * Transform this Exchange to another Exchange where queueing, and transformation of this `I` and `W`
   * is controlled by supplied WyeW.
   *
   * Please note the `W` queue of values to be sent to server is unbounded any may cause excessive heap usage, if the
   * remote system will read `W` too slow. If you want to control this flow, use rather `flow`.
   *
   * @param y WyeW to control queueing and transformation
   *
   */
  def wye[I2,W2](y: WyeW[W, I, W2, I2])(implicit S: Strategy = Strategy.DefaultStrategy): Exchange[I2, W2] =
    flow(y.attachL(collect { case \/-(i) => i }))

  /**
   * Transform this Exchange to another Exchange where queueing, flow control and transformation of this `I` and `W`
   * is controlled by supplied WyeW.
   *
   * Note this allows for fine-grained flow-control of written `W` to server based on Int passed to left side of
   * supplied WyeW that contains actual size of queued values to be written to server.
   *
   *
   * @param y WyeW to control queueing, flow control and transformation
   */
  def flow[I2,W2](y: WyeW[W, Int \/ I, W2, I2])(implicit S: Strategy = Strategy.DefaultStrategy): Exchange[I2, W2] = {
    val wq = async.boundedQueue[W](0)
    val w2q = async.boundedQueue[W2](0)

    def cleanup: Process[Task, Nothing] = eval_(wq.close) fby eval_(w2q.close)
    def receive: Process[Task, I] = self.read onComplete cleanup
    def send: Process[Task, Unit] = wq.dequeue to self.write

    def sendAndReceive =
      (((wq.size.discrete either receive).wye(w2q.dequeue)(y)(S) onComplete cleanup) either send).flatMap {
        case \/-(_)      => halt
        case -\/(-\/(o)) => eval_(wq.enqueueOne(o))
        case -\/(\/-(b)) => emit(b)
      }

    new Exchange[I2, W2] {
      def read: Process[Task, I2] = sendAndReceive
      def write: scalaz.stream.Sink[Task, W2] = w2q.enqueue
    }

  }

  /**
   * Transforms this exchange to another exchange, that for every received `I` will consult supplied Writer1
   * and eventually transforms `I` to `I2` or to `W` that is sent to remote system.
   *
   *
   * Please note that if external system is slow on reading `W` this can lead to excessive heap usage. If you
   * want to avoid for this to happen, please use `wyeFlow` instead.
   *
   * @param w  Writer that processes received `I` and either echoes `I2` or writes `W` to external system
   *
   */
  def readThrough[I2](w: Writer1[W, I, I2])(implicit S: Strategy = Strategy.DefaultStrategy) : Exchange[I2,W]  = {
    def liftWriter : WyeW[W, Int \/ I, W, I2] = {
      def go(cur:Writer1[W, I, I2]):WyeW[W, Int \/ I, W, I2] = {
        awaitBoth[Int\/I,W].flatMap{
          case ReceiveL(-\/(_)) => go(cur)
          case ReceiveL(\/-(i)) =>
            cur.feed1(i).unemit match {
              case (out,hlt@Halt(rsn)) => emitSeq(out, hlt)
              case (out,next) => emitSeq(out) fby go(next)
            }
          case ReceiveR(w) => tell(w) fby go(cur)
          case HaltL(rsn) => Halt(rsn)
          case HaltR(rsn) => go(cur)
        }
      }
      go(w)
    }

    self.flow[I2,W](liftWriter)(S)
  }

}
