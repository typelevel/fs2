package streams

import org.scalacheck.Prop._
import org.scalacheck._

import java.util.concurrent.atomic.AtomicInteger

class StreamsSpec extends Properties("Stream") {

  import Stream._

  case object FailWhale extends RuntimeException("the system... is down")

  property("empty") = secure { empty === Vector() }
  property("emit(1)") = secure { emit(1) === Vector(1) }
  property("chunk(1,2)") = secure { chunk(Chunk.seq(Vector(1,2))) === Vector(1,2) }
  property("++") = secure { emit(1) ++ emit(2) === Vector(1,2) }
  property("left-associated ++") = secure {
    val N = 100000
    (1 until N).map(emit).foldLeft(emit(0))(_ ++ _) === Vector.range(0,N)
  }
  property("right-associated ++") = secure {
    val N = 100000
    Chunk.seq((0 until N).map(emit)).foldRight(empty: Stream[Nothing,Int])(_ ++ _) ===
    Vector.range(0,N)
  }

  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[A](s: Stream[Task,A]) {
    def ===(v: Vector[A]) = run(s) == v
  }
}
