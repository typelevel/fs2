package fs2

import scala.concurrent.duration._
import scala.scalajs.js.JSApp

object Scratch extends JSApp {
  def main(): Unit = {
    implicit val strategy: Strategy = Strategy.default
    implicit val scheduler: Scheduler = Scheduler.default

    println(Stream(1, 2, 3).toList)
    println(Stream(1, 2, 3).toVector)
    Stream.repeatEval(Task(scala.util.Random.nextInt(20))).through(pipe.prefetch).filter(_ >= 10).take(10).runLog.unsafeRunAsync(println)
    time.awakeEvery[Task](100.millis).map(_.toMillis/100).take(5).runLog.unsafeRunAsync(println)
  }
}
