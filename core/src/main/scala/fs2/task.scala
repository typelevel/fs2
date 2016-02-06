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
  def async1[O](register: (Either[Throwable,O] => Unit) => Unit)(implicit S: Strategy): Stream[Task,O] =
    Stream.eval(Task.async(register))

  // todo: I have a feeling that strictly returning an `Ack` will not
  // be possible, might need to do:
  // def queue(register: (Input[O], Ack[O] => Unit) => Unit): Task[Stream[Task,O]]

  /**
   * Function for converting a multiple-use callback to a `Stream`.
   *
   * The output `Stream` has queue-like semantics--chunks added to the queue
   * accumulate until they are consumed in LIFO order, and in the event of
   * multiple consumers, chunks will be delivered to at most one consumer.
   */
  def queue[O](register: (Input[O] => Ack[O]) => Unit): Task[Stream[Task,O]] = ???

  /**
   * Function for converting a multiple-use callback to a `Stream`.
   *
   * The output `Stream` has signal-like semantics--chunks are retained only
   * until the next produced chunk arrives, and may not be seen by any consumer
   * depending on the consumer's rate of processing. In the event of multiple
   * consumers, chunks will be delivered to potentially all consumers.
   */
  def signal[O](w: O)(register: (Input[O] => Ack[O]) => Unit): Task[Stream[Task,O]] = ???
}
