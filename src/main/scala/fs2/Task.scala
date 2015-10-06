package fs2

import fs2.util.Task

object task {
  trait Ack[+W]
  object Ack {
    case class Accept(unprocessedSize: Int) extends Ack[Nothing]
    case class Halt[W](unprocessed: Seq[W]) extends Ack[W]
    case class Fail[W](err: Throwable, unprocessed: Seq[W]) extends Ack[W]
  }

  trait Input[+W]
  object Input {
    case class Send[W](chunk: Chunk[W]) extends Input[W]
    case object Done extends Input[Nothing]
    case class Fail(err: Throwable) extends Input[Nothing]
  }

  /** Convert a single use callback-y API to a single-element stream. */
  def async1[O](register: (Either[Throwable,O] => Unit) => Unit): Stream[Task,O] =
    Stream.eval(Task.async(register))

  /**
   * Function for converting a multiple-use callback to a `Stream`.
   * The `register` parameter will invoke the given callback multiple times
   * with an `Input[O]`, which can be used to control termination of the
   * output `Stream`.
   *
   * The output `Stream` has queue-like semantics. If the returned `Stream` is
   * reference in multiple locations, each chunk sent by the callback will be consumed
   * by only one such location.
   */
  def asyncs[O](register: (Input[O] => Ack[O]) => Unit): Task[Stream[Task,O]] = ???

  def signal[O](w: O)(register: (Input[O] => Ack[O]) => Unit): Task[Stream[Task,O]] = ???
}
