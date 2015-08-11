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
  // currently hangs
  // property("++") = secure { emit(1) ++ emit(2) === Vector(1,2) }

  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[A](s: Stream[Task,A]) {
    def ===(v: Vector[A]) = run(s) == v
  }
}
