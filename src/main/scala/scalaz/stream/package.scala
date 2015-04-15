package scalaz

import java.util.concurrent.{ThreadFactory, Executors}

import scodec.bits.ByteVector

import scalaz.stream.Process.Env

package object stream {

  type Process0[+O] = Process[Nothing,O]

  /**
   * A single input stream transducer. Accepts input of type `I`,
   * and emits values of type `O`.
   */
  type Process1[-I,+O] = Process[Env[I,Any]#Is, O]

  /**
   * A stream transducer that can read from one of two inputs,
   * the 'left' (of type `I`) or the 'right' (of type `I2`).
   * `Process1[I,O] <: Tee[I,I2,O]`.
   */
  type Tee[-I,-I2,+O] = Process[Env[I,I2]#T, O]

  /**
   * A stream transducer that can read from one of two inputs,
   * non-deterministically.
   */
  type Wye[-I,-I2,+O] = Process[Env[I,I2]#Y, O]

  /**
   * An effectful sink, to which we can send values. Modeled
   * as a source of effectful functions.
   */
  type Sink[+F[_],-O] = Process[F, O => F[Unit]]

  /**
   * An effectful channel, to which we can send values and
   * get back responses. Modeled as a source of effectful
   * functions.
   */
  type Channel[+F[_],-I,O] = Process[F, I => F[O]]




  /**
   * A `Writer[F,W,O]` is a `Process[F, W \/ O]`. See
   * `WriterSyntax` for convenience functions
   * for working with either the written values (the `W`)
   * or the output values (the `O`).
   *
   * This is useful for logging or other situations where we
   * want to emit some values 'on the side' while doing something
   * else with the main output of a `Process`.
   */
  type Writer[+F[_],+W,+O] = Process[F, W \/ O]

  /** A `Process1` that writes values of type `W`. */
  type Writer1[+W,-I,+O] = Process1[I,W \/ O]

  /** A `Tee` that writes values of type `W`. */
  type TeeW[+W,-I,-I2,+O] = Tee[I,I2,W \/ O]

  /** A `Wye` that writes values of type `W`. */
  type WyeW[+W,-I,-I2,+O] = Wye[I,I2,W \/ O]

  /**
   * Scheduler used for timing processes.
   * This thread pool shall not be used
   * for general purpose Process or Task execution
   */
  val DefaultScheduler = {
    Executors.newScheduledThreadPool(Runtime.getRuntime.availableProcessors() max 4, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scalaz-stream-default-scheduler")
        t
      }
    })
  }

  implicit val byteVectorSemigroupInstance: Semigroup[ByteVector] =
    Semigroup.instance(_ ++ _)

}
