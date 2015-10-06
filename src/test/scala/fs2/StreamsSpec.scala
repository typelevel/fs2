package fs2

import org.scalacheck.Prop._
import org.scalacheck._

import java.util.concurrent.atomic.AtomicInteger
import fs2.util._

class StreamsSpec extends Properties("Stream") {

  import Stream._

  case object FailWhale extends RuntimeException("the system... is down")

  val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)

  property("empty") = secure { empty === Vector() }

  property("emit(1)") = secure { emit(1) === Vector(1) }

  property("chunk(1,2)") = secure { chunk(Chunk.seq(Vector(1,2))) === Vector(1,2) }

  property("++") = secure { emit(1) ++ emit(2) === Vector(1,2) }

  def ranges(N: Int): List[Stream[Nothing,Int]] = List(
    // left associated ++
    (1 until N).map(emit).foldLeft(emit(0))(_ ++ _),
    // right associated ++
    Chunk.seq((0 until N) map emit).foldRight(empty: Stream[Nothing,Int])(_ ++ _)
  )

  property("map") = secure {
    Ns.forall { N =>
      ranges(N).forall(s => (s map (_ + 1)) === Vector.range(1, N+1))
    }
  }

  include (new Properties("O(1) and stack safety of `++` and `flatMap`") {
    property("left-associated ++") = secure { Ns.forall { N =>
     (1 until N).map(emit).foldLeft(emit(0))(_ ++ _) ===
     Vector.range(0,N)
    }}

    property("right-associated ++") = secure { Ns.forall { N =>
      Chunk.seq((0 until N).map(emit)).foldRight(empty: Stream[Nothing,Int])(_ ++ _) ===
      Vector.range(0,N)
    }}

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
  })

  property("transduce (id)") = secure {
    Ns.forall { N => logTime("transduce (id) " + N) {
      (chunk(Chunk.seq(0 until N)): Stream[Task,Int]).repeatPull { (s: Handle[Task,Int]) =>
        for {
          s2 <- s.await1
          _ <- Pull.write1(s2.head)
        } yield s2.tail
      } ==? Vector.range(0,N) }
    }
  }

  property("fail (1)") = secure {
    throws (FailWhale) { fail(FailWhale) }
  }

  property("fail (2)") = secure {
    throws (FailWhale) { emit(1) ++ fail(FailWhale) }
  }

  property("onError (1)") = secure {
    (fail(FailWhale) onError { _ => emit(1) }) === Vector(1)
  }

  property("onError (2)") = secure {
    (emit(1) ++ fail(FailWhale) onError { _ => emit(1) }) === Vector(1,1)
  }

  property("bracket (1)") = secure {
    val ok = new AtomicInteger(0)
    val bracketed = bracket(Task.delay(()))(
      _ => emit(1) ++ fail(FailWhale),
      _ => Task.delay { ok.incrementAndGet; () }
    )
    throws (FailWhale) { bracketed } && ok.get == 1
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
  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  def throws[A](err: Throwable)(s: Stream[Task,A]): Boolean =
    s.runLog.run.attemptRun match {
      case Left(e) if e == err => true
      case _ => false
    }

  implicit class EqualsOp[A](s: Stream[Task,A]) {
    def ===(v: Vector[A]) = run(s) == v
    def ==?(v: Vector[A]) = {
      val l = run(s)
      val r = v
      l == r || { println("left: " + l); println("right: " + r); false }
    }
  }
}
