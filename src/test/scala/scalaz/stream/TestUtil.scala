package scalaz.stream

import scala.concurrent.duration._
import java.util.concurrent.{ThreadFactory, Executors, ExecutorService}
import java.util.concurrent.atomic.AtomicInteger
import scalaz.concurrent.Task
import scalaz.stream.Process.Halt

/**
 * Various testing helpers
 */
private[stream] object TestUtil {

  /** simple timing test, returns the duration and result */
  def time[A](a: => A, l: String = ""): (FiniteDuration, A) = {
    val start = System.currentTimeMillis()
    val result = a
    val stop = System.currentTimeMillis()
     println(s"$l took ${(stop - start) / 1000.0 } seconds")
    ((stop - start).millis, result)
  }

  /** like `time` but will return time per item based on times supplied */
  def timePer[A](items:Int)(a: => A, l: String = ""): (FiniteDuration, A) = {
    val (tm, ra) = time(a,l)
    (tm / items, ra)
  }

  val DefaultSpecExecutorService: ExecutorService = {
    val threadIndex = new AtomicInteger(0);

    Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors max 32, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = new Thread(r,s"stream-spec-${threadIndex.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    })
  }

  case class UnexpectedCause( c:Cause, tag:String = "check") extends RuntimeException {
    override def getMessage: String = s"UnexpectedCause: $c at : $tag"
    override def toString: String = getMessage
  }



  implicit class ExpectExn[O](val p: Process0[O]) extends AnyVal {
    def expectedCause(pred: Cause => Boolean): Process0[O] = p.onHalt {
      cause =>
        if (pred(cause)) Process.halt
        else Process.fail(UnexpectedCause(cause))
    }
  }

  implicit class ExpectExnTask[O](val p: Process[Task, O]) extends AnyVal {
    def expectedCause(pred: Cause => Boolean): Process[Task, O] = p.onHalt {
      cause =>
        if (pred(cause)) Process.halt
        else Process.fail(UnexpectedCause(cause))
    }
  }
}
