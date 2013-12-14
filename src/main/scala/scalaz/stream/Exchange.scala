package scalaz.stream

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.stream._

/**
 * Created by pach on 12/13/13.
 */
trait Exchange[I,O] {

  /**
   * Provides process, that when run will supply received values `I` from external system
   *@return
   */
  def receive:Process[Task,I]

  /**
   * Provides sink, that send the `O` messages to external system
   * @return
   */
  def send:Sink[Task,O]


  /**
   * `pipes` Exchange sides through supplied processes1. Supplied processes are used to transform received and
   * send messages for tasks like message validation, serialization and de-serialization
   * @param rcv   Process1 to be used on incoming (received) data
   * @param snd   Process1 to be used on outgoing (sent) data
   */
  def pipe[A,B](rcv:Process1[I,A], snd:Process1[B,O]) : Exchange[A,B] = ???


  /**
   * Runs the exchange through supplied writer, that for each `I` produces either `O` that is sent to remote party
   * or `A` that is sent to client.
   * @param w  Writer thap processes received `I`
   *
   */
  def throughW[A](w:Process1W[O,I,A]):Process[Task,A] = ???


  /**
   * Runs the exchange through supplied wye writer, merging with supplied stream. If Wye writer supplies `O` it is sent to
   * server. If WyeW supplies `B` it is sent to resulting process
   * @param p     Process to be consulted in wye
   * @param y     wye that merges `I` received from server or the `A`
   */
  def wye[A,B,C](p:Process[Task,A])(y:WyeW[O,I,A,B]) : Process[Task,B] = ???


}
