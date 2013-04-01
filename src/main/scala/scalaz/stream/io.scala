package scalaz.stream

import java.io.{InputStream,OutputStream}

import scalaz.concurrent.Task
import Process._

/** 
 * Module of `Process` functions and combinators for file and network I/O.
 */
trait io {

  /* 
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

  /* 
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
   * Create a `Process[Task,String]` from an `InputStream`, by 
   * repeatedly requesting chunks of size `n`. The last chunk may 
   * have size less than `n`. This implementation reuses the
   * same buffer for consecutive reads, which is only safe if 
   * whatever consumes this `Process` never stores the `Bytes` 
   * returned or passes it to a combinator (like `buffer`) that
   * does. Use `chunkR` for a safe version of this combinator.
   * 
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def unsafeChunkR(n: Int)(is: => InputStream): Process[Task,Bytes] = {
    require(n > 0, "chunk size must be greater than 0, was: " + n)
    resource(Task.delay((is, new Array[Byte](n))))(
             src => Task.delay(src._1.close)) { case (src,buf) => 
      Task.delay { val m = src.read(buf); if (m == -1) throw End else new Bytes(buf, m) } 
    }
  }

  /** 
   * Create a `Process[Task,String]` from an `InputStream`, by 
   * repeatedly requesting chunks of size `n`. The last chunk may 
   * have size less than `n`.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error. 
   */
  def chunkR(n: Int)(is: => InputStream): Process[Task, Array[Byte]] =
    unsafeChunkR(n)(is).map(_.toArray)

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
              case e: Exception => err ++ failTask(e) // Helper function, defined below
            }
          go(next, acc)
      }
    go(src, IndexedSeq()) 
  }

  def failTask[O](e: Throwable): Process[Task,O] = 
    await[Task,O,O](Task(throw e))()
}

object io extends io
