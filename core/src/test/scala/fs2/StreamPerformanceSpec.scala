package fs2

import org.scalacheck.Prop.{throws => _, _}
import org.scalacheck._

import java.util.concurrent.atomic.AtomicInteger
import fs2.util._
import fs2.TestUtil._

object StreamPerformanceSpec extends Properties("StreamPerformance") {

  import Stream._

  case object FailWhale extends RuntimeException("the system... is down")

  val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)

  def ranges(N: Int): List[Stream[Pure,Int]] = List(
    // left associated ++
    (1 until N).map(emit).foldLeft(emit(0))(_ ++ _),
    // right associated ++
    Chunk.seq((0 until N) map emit).foldRight(empty: Stream[Pure,Int])(_ ++ _)
  )

  property("left-associated ++") = secure { Ns.forall { N =>
   logTime("left-associated ++ ("+N.toString+")") {
   (1 until N).map(emit).foldLeft(emit(0))(_ ++ _) ===
   Vector.range(0,N)
  }}}

  property("right-associated ++") = secure { Ns.forall { N =>
    logTime("right-associated ++ ("+N.toString+")") {
    Chunk.seq((0 until N).map(emit)).foldRight(empty: Stream[Pure,Int])(_ ++ _) ===
    Vector.range(0,N)
  }}}

  property("left-associated flatMap 1") = secure {
    Ns.forall { N => logTime("left-associated flatMap 1 ("+N.toString+")") {
      (1 until N).map(emit).foldLeft(emit(0))(
        (acc,a) => acc flatMap { _ => a }) ===
      Vector(N-1)
    }}
  }

  property("right-associated flatMap 1") = secure {
    Ns.forall { N => logTime("right-associated flatMap 1 ("+N.toString+")") {
      (1 until N).map(emit).reverse.foldLeft(emit(0))(
        (acc,a) => a flatMap { _ => acc }) ===
      Vector(0)
    }}
  }

  property("left-associated flatMap 2") = secure {
    Ns.forall { N => logTime("left-associated flatMap 2 ("+N.toString+")") {
      (1 until N).map(emit).foldLeft(emit(0) ++ emit(1) ++ emit(2))(
        (acc,a) => acc flatMap { _ => a }) ===
      Vector(N-1, N-1, N-1)
    }}
  }

  property("right-associated flatMap 1") = secure {
    Ns.forall { N => logTime("right-associated flatMap 1 ("+N.toString+")") {
      (1 until N).map(emit).reverse.foldLeft(emit(0) ++ emit(1) ++ emit(2))(
        (acc,a) => a flatMap { _ => acc }) === Vector(0,1,2)
    }}
  }

  property("transduce (id)") = secure {
    Ns.forall { N => logTime("transduce (id) " + N) {
      (chunk(Chunk.seq(0 until N)): Stream[Task,Int]).repeatPull { (s: Handle[Task,Int]) =>
        for {
          s2 <- s.await1
          _ <- Pull.output1(s2.head)
        } yield s2.tail
      } ==? Vector.range(0,N) }
    }
  }

  property("bracket + onError (1)") = secure { Ns.forall { N =>
    val open = new AtomicInteger(0)
    val ok = new AtomicInteger(0)
    val bracketed = bracket(Task.delay { open.incrementAndGet })(
      _ => emit(1) ++ fail(FailWhale),
      _ => Task.delay { ok.incrementAndGet; open.decrementAndGet; () }
    )
    // left-associative onError chains
    throws (FailWhale) {
      List.fill(N)(bracketed).foldLeft(fail(FailWhale): Stream[Task,Int]) {
        (acc,hd) => acc onError { _ => hd }
      }
    } &&
    ok.get == N && open.get == 0 && { ok.set(0); true } &&
    // right-associative onError chains
    throws (FailWhale) {
      List.fill(N)(bracketed).foldLeft(fail(FailWhale): Stream[Task,Int]) {
        (tl,hd) => hd onError { _ => tl }
      }
    } && ok.get == N && open.get == 0
  }}

  def logTime[A](msg: String)(a: => A): A = {
    val start = System.nanoTime
    val result = a
    val total = (System.nanoTime - start) / 1e6
    println(msg + " took " + total + " milliseconds")
    result
  }
}
