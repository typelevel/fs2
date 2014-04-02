package scalaz.stream

import Process._
import processes._
import scalaz.\/._
import scalaz._
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process.Halt
import scalaz.stream.ReceiveY.HaltL
import scalaz.stream.ReceiveY.HaltR
import scalaz.stream.ReceiveY.ReceiveL
import scalaz.stream.ReceiveY.ReceiveR
import scalaz.stream.{wye => _wye}
import scalaz.stream.wye.Request

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
 * Exchange is currently specialized to `scalaz.concurrent.Task`
 *
 * @param read Process reading values from remote system
 * @param write Process writing values to remote system
 *
 * @tparam I  values read from remote system
 * @tparam W  values written to remote system
 */
final case class Exchange[I, W](read: Process[Task, I], write: Sink[Task, W]) {

  self =>

  //alphabetical order from now on

  /**
   * uses provided function `f` to be applied on any `I` received
   */
  def mapO[I2](f: I => I2): Exchange[I2, W] =
    Exchange(self.read map f, self.write)

  /**
   * applies provided function to any `W2` that has to be written to provide an `W`
   */
  def mapW[W2](f: W2 => W): Exchange[I, W2] =
    Exchange(self.read, self.write contramap f)


  /**
   * Creates exchange that `pipe` read `I` values through supplied p1.
   * @param p1   Process1 to be used when reading values
   */
  def pipeO[I2](p1: Process1[I, I2]): Exchange[I2, W] =
    Exchange(self.read.pipe(p1), self.write)


  /**
   * Creates new exchange, that pipes all values to be sent through supplied `p1`
   */
  def pipeW[W2](p1: Process1[W2, W]): Exchange[I, W2] =
    Exchange(self.read, self.write.pipeIn(p1))

  /**
   * Creates new exchange, that will pipe read values through supplied `r` and write values through `w`
   * @param r  Process1 to use on all read values
   * @param w  Process1 to use on all written values
   */
  def pipeBoth[I2, W2](r: Process1[I, I2], w: Process1[W2, W]): Exchange[I2, W2] =
    self.pipeO(r).pipeW(w)


  /**
   * Runs supplied Process of `W` values by sending them to remote system.
   * Any replies from remote system are received as `I` values of the resulting process.
   *
   * Please note this will terminate by default after Left side (receive) terminates.
   * If you want to terminate after Right side (W) terminates, supply terminateOn with `Request.R` or `Request.Both` to
   * terminate on Right or Any side respectively
   *
   * @param p
   * @return
   */
  def run(p:Process[Task,W] = halt, terminateOn:Request = Request.L):Process[Task,I] = {
    val y = terminateOn match {
      case Request.L => _wye.mergeHaltL[I]
      case Request.R => _wye.mergeHaltR[I]
      case Request.Both => _wye.mergeHaltBoth[I]
    }
    self.read.wye((p to self.write).drain)(y)
  }


  /**
   * Creates Exchange that runs read `I` through supplied effect channel.
   * @param ch Channel producing process of `I2` for each `I` received
   */
  def through[I2](ch: Channel[Task, I, Process[Task, I2]]): Exchange[I2, W] =
    Exchange((self.read through ch) flatMap identity, self.write)

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

    def cleanup: Process[Task, Nothing] = eval_(wq.close) fby eval_(Task.delay(w2q.close.runAsync(_ => ())))
    def receive: Process[Task, I] = self.read onComplete cleanup
    def send: Process[Task, Unit] = wq.dequeue to self.write

    def sendAndReceive = {
      val (o, ny) = y.unemit
      (emitSeq(o) fby ((wq.size.discrete either receive).wye(w2q.dequeue)(ny)(S) onComplete cleanup) either send).flatMap {
        case \/-(o) => halt
        case -\/(-\/(o)) => eval_(wq.enqueueOne(o))
        case -\/(\/-(b)) => emit(b)
      }
    }

    Exchange(sendAndReceive, w2q.enqueue)
  }

  /**
   * Transforms this exchange to another exchange, that for every received `I` will consult supplied Writer1
   * and eventually transforms `I` to `I2` or to `W` that is sent to remote system.
   *
   *
   * Please note that if external system is slow on reading `W` this can lead to excessive heap usage. If you
   * want to avoid for this to happen, please use `flow` instead.
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

object Exchange {

  /**
   * Provides `loopBack` exchange, that loops data written to it back to `read` side, via supplied Process1.
   *
   * If process1 starts with emitting some data, they are `read` first.
   *
   * This exchange will terminate `read` side once the `p` terminates.
   *
   * Note that the `write` side is run off the thread that actually writes the messages, forcing the `read` side
   * to be run on different thread.
   *
   * This primitive may be used also for asynchronous processing that needs to be forked to different thread.
   *
   * @param p   Process to consult when looping data through
   */
  def loopBack[I, W](p: Process1[W, I])(implicit S: Strategy = Strategy.DefaultStrategy): Process[Task, Exchange[I, W]] = {

    def loop(cur: Process1[W, I]): WyeW[Nothing, Nothing, W, I] = {
      awaitR[W] flatMap {
        case w => cur.feed1(w).unemit match {
          case (o, hlt@Halt(rsn)) => emitSeq(o.map(right)) fby hlt
          case (o, np)            => emitSeq(o.map(right)) fby loop(np)
        }
      }
    }

    await(Task.delay {
      async.boundedQueue[W]()
    })({ q =>
      val (out, np) = p.unemit
      val ex = Exchange[Nothing, W](halt, q.enqueue)
      emit(ex.wye(emitSeq(out).map(right) fby loop(np))) onComplete eval_(Task.delay(q.close.run))
    })

  }


  /**
   * Exchange that is always halted
   */
  def halted[I,W]: Exchange[I,W] = Exchange(halt,halt)

}
