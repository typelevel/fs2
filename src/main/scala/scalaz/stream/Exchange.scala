package scalaz.stream

import Process._
import scalaz.concurrent.{Strategy, Task}
import scalaz.syntax.monad._
import scalaz.{\/, \/-, -\/}
import scalaz.\/._

/**
 * Exchange represents interconnection between two systems.
 * So called remote resource `R` is resource from which program receives messages of type `I`
 * and can send to it messages of type `O`.
 *
 * Typically this can be sort of connection to external system,
 * like for example tcp connection.
 *
 * Exchange allows combining this pattern with current Processes,
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
   * Provides process, that when run will supply received values `I` from external system
   *@return
   */
  private[stream] def receive: Process[Task, I]

  /**
   * Provides sink, that send the `O` messages to external system
   * @return
   */
  private[stream] def send: Sink[Task, O]


  /**
   * `pipes` Exchange sides through supplied processes1. Supplied processes are used to transform received and
   * send messages for tasks like message validation, serialization and de-serialization
   * @param rcv   Process1 to be used on incoming (received) data
   * @param snd   Process1 to be used on outgoing (sent) data
   */
  def pipe[A, B](rcv: Process1[I, A], snd: Process1[B, O]): Exchange[A, B] =
    new Exchange[A, B] {
      def receive: Process[Task, A] = self.receive.pipe(rcv)
      def send: Sink[Task, B] = {
        constant {
          @volatile var cur: Process1[B, O] = snd //safe here hence at no moment 2 threads may access this at same time
          (b: B) => Task.now[Unit] {
            val (out, next) = cur.feed1(b).unemit
            cur = next
            (emitSeq(out).toSource to self.send).run
          }
        }
      }
    }

  /**
   * Runs this exchange through supplied channel.
   * For every `I` received the channel should produce process of `O` that can be sent back
   *
   * It produces Writer, that when run will on right side contain any data written to exchange and on left side
   * Any data received from exchange
   * @param ch channel to run this exchange through
   */
  def through(ch: Channel[Task, I, Process[Task, O]]): Writer[Task, I, O] = {
    val rch : Channel[Task, I, Process[Task, I \/ O]]  = ch.map(f=> (i:I) =>  f(i).map(p=>emit(-\/(i)) ++ p.map(o=>right(o))))
    val sendCh : Channel[Task, I \/ O, I \/ O] = self.send.map(f => (ivo: I \/ O) => ivo match {
      case -\/(i) => Task.now(-\/(i))
      case \/-(o) => f(o).map(_=> \/-(o))
    } )

    (receive through rch).flatMap(identity) through sendCh
  }


  /**
   * Runs the exchange through supplied writer, that for each `I` produces either `O` that is sent to remote party
   * or `A` that is sent to client.
   * @param w  Writer thap processes received `I`
   *
   */
  def throughW[A](w: Process1W[O, I, A]): Process[Task, A] =
    receive.pipe(w).flatMap {
      case -\/(o) => eval((emit(o).toSource to self.send).run).drain
      case \/-(a) => emit(a)
    }

  /**
   * Non-deterministically sends or receives data through supplied process and channel.
   * It produces Writer, that when run will on right side contain any data written to exchange and on left side
   * Any data received from exchange
   * @param p    Process, that acts as source of data
   * @param ch   Channel, that for every `I` received eventually produces
   * @return
   */
  def sendThrough(p:Process[Task,O])(ch: Channel[Task, I, Process[Task, O]])(S: Strategy = Strategy.DefaultStrategy) : Writer[Task,I,O] = {
    through(ch) merge (p through send.toChannel).map(\/-(_))
  }

  /**
   * Runs the exchange through supplied wye writer, merging with supplied stream. If Wye writer supplies `O` it is sent to
   * server. If WyeW supplies `B` it is sent to resulting process
   * @param p     Process to be consulted in wye
   * @param y     wye that merges `I` received from server or the `A`
   * @param S     Wye strategy
   */
  def wye[A, B, C](p: Process[Task, A])(y: WyeW[O, I, A, B])(S: Strategy = Strategy.DefaultStrategy): Process[Task, B] =
    self.receive.wye(p)(y)(S).flatMap {
      case -\/(o) => eval((emit(o).toSource to self.send).run).drain
      case \/-(b) => emit(b)
    }


}
