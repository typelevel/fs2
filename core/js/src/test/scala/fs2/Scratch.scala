package fs2

import scala.scalajs.js.JSApp

object Scratch extends JSApp {
  def main(): Unit = {
    println(Stream(1, 2, 3).toList)
    println(Stream(1, 2, 3).toVector)
    Stream.repeatEval(Task(scala.util.Random.nextInt(20))).through(pipe.prefetch).filter(_ >= 10).take(10).runLog.unsafeRunAsync(println)
  }
}
