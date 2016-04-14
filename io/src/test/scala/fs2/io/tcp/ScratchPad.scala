package fs2.io.tcp

import java.util.concurrent.atomic.AtomicInteger

import fs2._
import fs2.Stream._
import fs2.util.Task
import scala.concurrent.duration._

/**
  * Created by pach on 10/04/16.
  */
object ScratchPad extends App {
  import fs2.TestUtil._

  val open = new AtomicInteger(0)

  // this hangs
//  val source:Stream[Task,Stream[Task,Unit]] = Stream.emits(0 to 1000).map { idx =>
//    val sleep = (Math.random() * 1000).millis
//    eval(Task.delay(println(s"START($idx, ${sleep.toMillis}, ${open.incrementAndGet()})"))) ++
//    eval(Task.async[Unit](cb => Sch.schedule(new Runnable { def run(): Unit = cb(Right(())) }, sleep.toMillis, TimeUnit.MILLISECONDS))) ++
//      eval(Task.delay(println(s"DONE($idx, ${open.decrementAndGet()})")))
//  }

  // this hangs too
//  val source:Stream[Task,Stream[Task,Unit]] = Stream.emits(0 to 20).map { idx =>
//    val sleep = (Math.random() * 1000).millis
//    eval(Task.delay(println(s"START($idx, ${sleep.toMillis}, ${open.incrementAndGet()})"))) ++
//      time.sleep(sleep) ++
//      eval(Task.delay(println(s"DONE($idx, ${open.decrementAndGet()})")))
//  }


    val source:Stream[Task,Stream[Task,Unit]] = {
      def recursion(at:Int):Stream[Task,Int] = {
        time.sleep(100.millis) ++ emit(at) ++ recursion(at + 1)
      }
      (Stream.emits(0 to 20) zip time.awakeEvery(100.millis))
      //Stream.emits(0 to 20)
      recursion(0)
      .map { idx =>
        val sleep = (Math.random() * 1000).millis
        eval(Task.delay(println(s"START($idx, ${sleep.toMillis}, ${open.incrementAndGet()})"))) ++
          time.sleep(sleep) ++
          eval(Task.delay(println(s"DONE($idx, ${open.decrementAndGet()})")))
      }
    }



  concurrent.join(Int.MaxValue)(source).run.run.run


}
