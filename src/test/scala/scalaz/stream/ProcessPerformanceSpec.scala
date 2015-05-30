package scalaz.stream


import concurrent.duration._
import org.scalacheck.Prop._
import org.scalacheck.Properties
import scalaz.{\/, Monoid}
import scalaz.\/._
import scalaz.concurrent.{Strategy, Task}
import Process._

class ProcessPerformanceSpec extends Properties("Process-performance") {

  import TestUtil._

  object append {
    def left(n: Int): Process[Task, Int] =
      (0 to n).map(emit).foldLeft(emit(0))(_ ++ _)

    def right(n: Int): Process[Task, Int] =
      (0 to n).map(emit).reverse.foldLeft(emit(0))(
        (acc, h) => h ++ acc
      )
  }

  object flatMap {
    // check for left-associated binds
    def left1(n: Int): Process[Task, Int] =
      (0 to n).map(emit).foldLeft(emit(0))((acc, h) =>
        acc.flatMap(acc => h.map(h => acc + h)))


    // check for right-associated binds
    def right1(n: Int): Process[Task, Int] =
      (0 to n).reverse.foldLeft(emit(0))((acc, h) =>
        acc.flatMap(acc => emit(h + acc)))

    // check for left-associated binds with append
    def leftAppend(n: Int): Process[Task, Int] =
      if (n == 0) halt
      else emit(1).flatMap(_ => leftAppend(n - 1) ++ leftAppend(n - 1))

    // check for right-associated nested flatMap
    def rightNested(n: Int): Process[Task, Int] =
      (0 to n).reverse.map(emit).foldLeft(emit(0))((acc, h) =>
        h.flatMap(h => acc.map(_ + h)))
  }

  object worstCase {

    def churned(n: Int): Process[Task, Int] = {
      @annotation.tailrec
      def churn(p: Process[Task, Int], m: Int): Process[Task, Int] =
        if (m == 0) p
        else churn(p.flatMap(emit), m - 1) // does nothing, but adds another flatMap
      churn(append.left(n), n)
    }

  }


  implicit val B = Monoid.instance[Int]((a, b) => a + b, 0)

  val defaultDistribution = Seq(1, 10, 100, 1000, 10 * 1000, 100 * 1000, 1000 * 1000)

  def associationCheck(
    left: Int => Process[Task, Int]
    , right: Int => Process[Task, Int]
    , maxTime: FiniteDuration = 10 seconds
    , distribution: Seq[Int] = defaultDistribution) = {
    val leftTimed =
      distribution.map(cnt => time { left(cnt).runFoldMap(identity).run })

    val rightTimed =
      distribution.map(cnt => time { right(cnt).runFoldMap(identity).run })

   // Timing is left out for now, as we need to adapt for slow test systems
   // ("Left associated is < 1s per run" |: leftTimed.filter(_._1 > maxTime).isEmpty) &&
   //   ("Right associated is < 1s per run" |: rightTimed.filter(_._1 > maxTime).isEmpty) &&
      ("Both yields same results" |: leftTimed.map(_._2) == rightTimed.map(_._2))
  }

  def checkOne(
    f: Int => Process[Task, Int]
    , maxTime: FiniteDuration = 10 seconds
    , distribution: Seq[Int] = defaultDistribution
    ) = {
    val timed = distribution.map(cnt => time(f(cnt).runFoldMap(identity).run))

    // Timing out for now
    // "Ops take < 1s per run" |: timed.filter(_._1 > maxTime).isEmpty
    true
  }


  // these properties won't complete, in case of quadratic complexity
  property("append") = secure { associationCheck(append.left, append.right) }
  property("flatMap") = secure { associationCheck(flatMap.left1, flatMap.right1) }
  property("flatMap-append") = secure { checkOne(flatMap.leftAppend, distribution = Seq(14, 15, 16, 17, 18, 19, 20, 21)) }
  property("flatMap-nested") = secure { checkOne(flatMap.rightNested) }
  property("worstCase") = secure { checkOne(worstCase.churned, distribution = (Seq(1,2,4,8,16,32,64,128,256,512,1024,2048))) }



  property("Process.runLast.performance") = secure {
    implicit val scheduler = scalaz.stream.DefaultScheduler
    implicit val S = Strategy.DefaultStrategy
    val interruptSignal = async.signalOf(false)

    val t1:Task[Option[Int]\/Option[Long]] =
      Process.constant(1)
      .take(10000000)
      .toSource
      .onComplete(eval_(interruptSignal.set(true)))
      .runLast
      .map(left)

    val r = Runtime.getRuntime
    val used = r.totalMemory() - r.freeMemory()

    val mt:Task[Option[Int]\/Option[Long]] =
      interruptSignal.discrete.wye(scalaz.stream.time.awakeEvery(100.millis))(wye.interrupt)
      .map { dur => dur -> (r.totalMemory() - r.freeMemory()) }
      .scan((0l,0l)){ case ((sum,count),(_, memory)) => (sum + memory) -> (count + 1) }
      .runLast
      .map(_.map {case (total,count) => (total.toDouble/count).toLong})
      .map(right)


    val results = Task.gatherUnordered(Seq(Task.fork(t1),Task.fork(mt))).run

    (results.size ?= 2) :| "Both tasks completed" &&
      (results(1).toOption.flatten.map(_ - used).getOrElse(Long.MaxValue) < 250l *1024 *1024) :| "Max 250M of heap was used on average"
  }
}
