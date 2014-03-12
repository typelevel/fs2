package scalaz.stream

import java.io.{BufferedOutputStream,BufferedInputStream,FileInputStream,FileOutputStream,InputStream,OutputStream}

import scala.io.{Codec, Source}
import scalaz.concurrent.Task
import Process._

/**
 * Module of `Process` functions and combinators for file and network I/O.
 */
trait io {

  // NB: methods are in alphabetical order

  /**
   * Like resource, but the `release` action may emit a final value,
   * useful for flushing any internal buffers. NB: In the event of an
   * error, this final value is ignored.
   */
  def bufferedResource[R,O](acquire: Task[R])(
                            flushAndRelease: R => Task[O])(
                            step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Process[Task,O], onFailure: Process[Task,O]): Process[Task,O] =
      await[Task,O,O](step) (
        o => emit(o) ++ go(step, onExit, onFailure) // Emit the value and repeat
      , onExit                                      // Release resource when exhausted
      , onExit)                                     // or in event of error
    await(acquire)(r => {
      val onExit = suspend(eval(flushAndRelease(r)))
      val onFailure = onExit.drain
      go(step(r), onExit, onFailure)
    }, halt, halt)
  }

  /**
   * Implementation of resource for channels where resource needs to be
   * flushed at the end of processing.
   */
  def bufferedChannel[R,I,O](acquire: Task[R])(
                             flush: R => Task[O])(
                             release: R => Task[Unit])(
                             step: R => Task[I => Task[O]]): Channel[Task,Option[I],O] = {
    resource[R,Option[I] => Task[O]](acquire)(release) {
      r =>
        val s = step(r)
        Task.now {
          case Some(i) => s flatMap (f => f(i))
          case None => flush(r)
        }
    }
  }

  /** Promote an effectful function to a `Channel`. */
  def channel[A,B](f: A => Task[B]): Channel[Task, A, B] =
    Process.constant(f)

  /**
   * Creates a `Channel[Task,Int,Array[Byte]]` from an `InputStream` by
   * repeatedly requesting the given number of bytes. The last chunk
   * may be less than the requested size.
   *
   * This implementation requires an array allocation for each read.
   * To recycle the input buffer, use `unsafeChunkR`.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def chunkR(is: => InputStream): Channel[Task, Int, Array[Byte]] =
    unsafeChunkR(is).map(f => (n: Int) => {
      val buf = new Array[Byte](n)
      f(buf)
    })

  /**
   * Creates a `Sink` from an `OutputStream`, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(os: => OutputStream): Sink[Task, Array[Byte]] =
    resource(Task.delay(os))(os => Task.delay(os.close))(
      os => Task.now((bytes: Array[Byte]) => Task.delay(os.write(bytes))))

  /** Creates a `Sink` from a file name and optional buffer size in bytes. */
  def fileChunkW(f: String, bufferSize: Int = 4096): Sink[Task, Array[Byte]] =
    chunkW(new BufferedOutputStream(new FileOutputStream(f), bufferSize))

  /** Creates a `Channel` from a file name and optional buffer size in bytes. */
  def fileChunkR(f: String, bufferSize: Int = 4096): Channel[Task, Int, Array[Byte]] =
    chunkR(new BufferedInputStream(new FileInputStream(f), bufferSize))

  /** A `Sink` which, as a side effect, adds elements to the given `Buffer`. */
  def fillBuffer[A](buf: collection.mutable.Buffer[A]): Sink[Task,A] =
    channel((a: A) => Task.delay { buf += a })

  /**
   * Creates a `Process[Task,String]` from the lines of a file, using
   * the `resource` combinator to ensure the file is closed
   * when processing the stream of lines is finished.
   */
  def linesR(filename: String)(implicit codec: Codec): Process[Task,String] =
    linesR(Source.fromFile(filename)(codec))

  /**
   * Creates a `Process[Task,String]` from the lines of the `InputStream`,
   * using the `resource` combinator to ensure the `InputStream` is closed
   * when processing the stream of lines is finished.
   */
  def linesR(in: InputStream)(implicit codec: Codec): Process[Task,String] =
    linesR(Source.fromInputStream(in)(codec))

  /**
   * Creates a `Process[Task,String]` from the lines of the `Source`,
   * using the `resource` combinator to ensure the `Source` is closed
   * when processing the stream of lines is finished.
   */
  def linesR(src: Source): Process[Task,String] =
    resource(Task.delay(src))(src => Task.delay(src.close)) { src =>
      lazy val lines = src.getLines // A stateful iterator
      Task.delay { if (lines.hasNext) lines.next else throw End }
    }

  /**
   * Generic combinator for producing a `Process[Task,O]` from some
   * effectful `O` source. The source is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `linesR` for an example use.
   */
  def resource[R,O](acquire: Task[R])(
                    release: R => Task[Unit])(
                    step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Process[Task,O]): Process[Task,O] =
      await[Task,O,O](step) (
        o => emit(o) ++ go(step, onExit) // Emit the value and repeat
      , onExit                           // Release resource when exhausted
      , onExit)                          // or in event of error
    await(acquire)(r => {
      val onExit = Process.suspend(eval(release(r)).drain)
      go(step(r), onExit)
    }, halt, halt)
  }

  /**
   * The standard input stream, as `Process`. This `Process` repeatedly awaits
   * and emits lines from standard input.
   */
  def stdInLines: Process[Task,String] =
    Process.repeatEval(Task.delay { Option(readLine()).getOrElse(throw End) })

  /**
   * The standard output stream, as a `Sink`. This `Sink` does not
   * emit newlines after each element. For that, use `stdOutLines`.
   */
  def stdOut: Sink[Task,String] =
    channel((s: String) => Task.delay { print(s) })

  /**
   * The standard output stream, as a `Sink`. This `Sink` emits
   * newlines after each element. If this is not desired, use `stdOut`.
   */
  def stdOutLines: Sink[Task,String] =
    channel((s: String) => Task.delay { println(s) })

  /**
   * Creates a `Channel[Task,Array[Byte],Array[Byte]]` from an `InputStream` by
   * repeatedly filling the input buffer. The last chunk may be less
   * than the requested size.
   *
   * It is safe to recycle the same buffer for consecutive reads
   * as long as whatever consumes this `Process` never stores the `Array[Byte]`
   * returned or pipes it to a combinator (like `buffer`) that does.
   * Use `chunkR` for a safe version of this combinator - this takes
   * an `Int` number of bytes to read and allocates a fresh `Array[Byte]`
   * for each read.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def unsafeChunkR(is: => InputStream): Channel[Task,Array[Byte],Array[Byte]] = {
    resource(Task.delay(is))(
             src => Task.delay(src.close)) { src =>
      Task.now { (buf: Array[Byte]) => Task.delay {
        val m = src.read(buf)
        if (m == -1) throw End
        else buf.take(m)
      }}
    }
  }
}

object io extends io with gzip
