package fs2

import TestUtil._
import fs2.util.Task

object Scratch extends App {
    val s = Stream(1,2,3)
          . evalMap(i => Task.delay {
            println("sleeping a/"+i); Thread.sleep(1000); println("done a/"+i); i })
          . through(process1.prefetch)
          . flatMap { i => Stream.eval(Task.delay { println("sleeping b/"+i); Thread.sleep(1000); println("done b/"+i); i }) }
    val start = System.currentTimeMillis
    run(s)
    val stop = System.currentTimeMillis
    println("prefetch (timing) took " + (stop-start) + " milliseconds")
}
