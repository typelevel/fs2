package scalaz.stream

import java.io.{InputStream,OutputStream}

import scalaz.concurrent.Task
import Process._

/** 
 * Module of `Process` functions and combinators for file and network I/O.
 */
trait io {

  /** 
   * Generic combinator for producing a `Process[Task,O]` from some
   * effectful `O` source. The source is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `lines` below for an example use. 
   */
  def resource[R,O](acquire: Task[R])(
                    release: R => Task[Unit])(
                    step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Task[Unit]): Process[Task,O] =
      await[Task,O,O](step) ( 
        o => emit(o) ++ go(step, onExit) // Emit the value and repeat 
      , await[Task,Unit,O](onExit)()  // Release resource when exhausted
      , await[Task,Unit,O](onExit)()) // or in event of error
    await(acquire) ( r => go(step(r), release(r)), Halt, Halt )
  }

  /**
   * Implementation of resource for channels where resource needs to be
   * flushed at the end of processing. Flush gives option to return any 
   * leftover data that may be flushed on resource release to stream.
   * 
   * 
   * This channel is supposed to be used with throughAndFlush combinator
   */
  def flushChannel[R,I,O](acquire: Task[R])(
                        flush: R => Task[Option[O]])(
                        release: R => Task[Unit])(
                        step: R => Task[I => Task[O]]): Channel[Task,Option[I],Option[O]] = {
    resource[R,Option[I]=>Task[Option[O]]](acquire)(release) {
      r => 
        Task.delay {
          case Some(i) => step(r) flatMap (f=>f(i)) map (Some(_))
          case None => flush(r) 
      }
    }
  }

  /**
   * Convenience helper to get Array[Byte] out of Bytes 
   */
  def toByteArray: Process1[Bytes,Array[Byte]] = 
    processes.lift(_.toArray)

  /**
   * Convenience helper to produce Bytes from the Array[Byte] 
   */
  def fromByteArray : Process1[Array[Byte],Bytes] = 
    processes.lift(Bytes(_))

  /** 
   * Create a `Process[Task,String]` from the lines of a file, using
   * the `resource` combinator to ensure the file is closed
   * when processing the stream of lines is finished. 
   */
  def linesR(filename: String): Process[Task,String] = 
    resource(Task.delay(scala.io.Source.fromFile(filename)))(
             src => Task.delay(src.close)) { src => 
      lazy val lines = src.getLines // A stateful iterator 
      Task.delay { if (lines.hasNext) lines.next else throw End }
    }

  /** 
   * Create a `Channel[Task,Array[Byte],Bytes]` from an `InputStream` by 
   * repeatedly filling the input buffer. The last chunk may be less 
   * than the requested size.
   * 
   * Because this implementation returns a read-only view of the given
   * buffer, it is safe to recyle the same buffer for consecutive reads
   * as long as whatever consumes this `Process` never stores the `Bytes` 
   * returned or pipes it to a combinator (like `buffer`) that does. 
   * Use `chunkR` for a safe version of this combinator.
   * 
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def unsafeChunkR(is: => InputStream): Channel[Task,Array[Byte],Bytes] = {
    resource(Task.delay(is))(
             src => Task.delay(src.close)) { src =>
      Task.now { (buf: Array[Byte]) => Task.delay {
        val m = src.read(buf)
        if (m == -1) throw End 
        else new Bytes(buf, m) 
      }} 
    }
  }

  /** 
   * Create a `Channel[Task,Int,Bytes]` from an `InputStream` by 
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
      f(buf).map(_.toArray)
    })

  /** 
   * Create a `Sink` from an `OutputStream`, which will be closed
   * when this `Process` is halted. 
   */
  def chunkW(os: => OutputStream): Process[Task, Array[Byte] => Task[Unit]] = 
    resource(Task.delay(os))(os => Task.delay(os.close))(
      os => Task.now((bytes: Array[Byte]) => Task.delay(os.write(bytes))))

  /** 
   * A simple tail recursive function to collect all the output of a 
   * `Process[Task,O]`. Because `Task` has a `run` function,
   * we can implement this as a tail-recursive function. 
   */
  def collectTask[O](src: Process[Task,O]): IndexedSeq[O] = {
    @annotation.tailrec
    def go(cur: Process[Task,O], acc: IndexedSeq[O]): IndexedSeq[O] = 
      cur match {
        case Emit(h,t) => go(t, acc ++ h) 
        case Halt => acc
        case Await(req,recv,fb,err) =>
          val next = 
            try recv(req.run)
            catch { 
              case End => fb // Normal termination
              case e: Exception => err match {
                case Halt => throw e // ensure exception is eventually thrown;
                                     // without this we'd infinite loop
                case _ => err ++ wrap(Task.delay(throw e))
              }
            }
          go(next, acc)
      }
    go(src, IndexedSeq()) 
  }
}

object io extends io with gzip
