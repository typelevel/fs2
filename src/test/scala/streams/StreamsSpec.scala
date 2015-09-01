package streams

import org.scalacheck.Prop._
import org.scalacheck._

import java.util.concurrent.atomic.AtomicInteger

class StreamsSpec extends Properties("Stream") {

  import Stream._

  case object FailWhale extends RuntimeException("the system... is down")

  val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)

  property("empty") = secure { empty === Vector() }

  property("emit(1)") = secure { emit(1) === Vector(1) }

  property("chunk(1,2)") = secure { chunk(Chunk.seq(Vector(1,2))) === Vector(1,2) }

  property("++") = secure { emit(1) ++ emit(2) === Vector(1,2) }

  include (new Properties("O(1) and stack safety of `++` and `flatMap`") {
    property("left-associated ++") = secure { Ns.forall { N =>
     (1 until N).map(emit).foldLeft(emit(0))(_ ++ _) === Vector.range(0,N)
    }}

    property("right-associated ++") = secure { Ns.forall { N =>
      Chunk.seq((0 until N).map(emit)).foldRight(empty: Stream[Nothing,Int])(_ ++ _) ===
      Vector.range(0,N)
    }}

    property("left-associated flatMap 1") = secure {
      Ns.forall { N =>
        logTime("left-associated flatMap 1 ("+N.toString+")") {
          (1 until N).map(emit).foldLeft(emit(0))(
            // todo, this annotation shouldn't be needed
            (acc,a) => acc flatMap[Nothing,Int] { _ => a }) === Vector(N-1)
        }
      }
    }

    property("right-associated flatMap 1") = secure {
      Ns.forall { N =>
        logTime("right-associated flatMap 1 ("+N.toString+")") {
          (1 until N).map(emit).reverse.foldLeft(emit(0))(
            // todo, this annotation shouldn't be needed
            (acc,a) => a flatMap[Nothing,Int] { _ => acc }) === Vector(0)
        }
      }
    }

    property("left-associated flatMap 2") = secure {
      Ns.forall { N =>
        logTime("left-associated flatMap 2 ("+N.toString+")") {
          (1 until N).map(emit).foldLeft(emit(0) ++ emit(1) ++ emit(2))(
            // todo, this annotation shouldn't be needed
            (acc,a) => acc flatMap[Nothing,Int] { _ => a }) === Vector(N-1, N-1, N-1)
        }
      }
    }

    property("right-associated flatMap 1") = secure {
      Ns.forall { N =>
        logTime("right-associated flatMap 1 ("+N.toString+")") {
          (1 until N).map(emit).reverse.foldLeft(emit(0) ++ emit(1) ++ emit(2))(
            // todo, this annotation shouldn't be needed
            (acc,a) => a flatMap[Nothing,Int] { _ => acc }) === Vector(0,1,2)
        }
      }
    }
  })

  property("transduce (id)") = secure {
    List(1,2,3).forall { N => (chunk(Chunk.seq(0 until N)): Stream[Task,Int]).pull { (s: Handle[Task,Int]) =>
      for {
        s2 <- s.await1
        _ <- { println(s2.head + " " + math.random); Pull.write1(s2.head) }
      } yield s2.tail
    } === Vector.range(0,N) }
  }

  def logTime[A](msg: String)(a: => A): A = {
    val start = System.nanoTime
    val result = a
    val total = (System.nanoTime - start) / 1e6
    println(msg + " took " + total + " milliseconds")
    result
  }
  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[A](s: Stream[Task,A]) {
    def ===(v: Vector[A]) = run(s) == v
  }
}
