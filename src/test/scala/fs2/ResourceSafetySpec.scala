package fs2

import TestUtil._
import fs2.util.Task
import fs2.Stream.Handle
import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Prop._
import org.scalacheck._

object ResourceSafetySpec extends Properties("ResourceSafety") {

  implicit val S = Strategy.fromExecutionContext(scala.concurrent.ExecutionContext.global)

  case object Err extends RuntimeException("oh noes!!")

  implicit def arbPureStream[A:Arbitrary] = Arbitrary(TestUtil.PureStream.gen[A])

  case class Failure(tag: String, get: Stream[Task,Int])
  implicit def failingStreamArb: Arbitrary[Failure] = Arbitrary(
    Gen.oneOf[Failure](
      Failure("pure-failure", Stream.fail(Err)),
      Failure("failure-inside-effect", Stream.eval(Task.delay(throw Err))),
      Failure("failure-mid-effect", Stream.eval(Task.now(()).flatMap(_ => throw Err))),
      Failure("failure-in-pure-code", Stream.emit(42).map(_ => throw Err)),
      Failure("failure-in-pure-code(2)", Stream.emit(42).flatMap(_ => throw Err)),
      Failure("failure-in-pure-pull", Stream.emit[Task,Int](42).pull(h => throw Err)),
      Failure("failure-in-async-code",
        Stream.eval[Task,Int](Task.delay(throw Err)).pull(h => h.invAwaitAsync.flatMap(_.force)))
    )
  )

  property("pure fail") = secure {
    try { Stream.emit(0).flatMap(_ => Stream.fail(Err)) === Vector(); false }
    catch { case Err => true } // expected
  }

  def spuriousFail(s: Stream[Task,Int], f: Failure): Stream[Task,Int] =
    s.flatMap { i => if (i % (math.random * 10 + 1).toInt == 0) f.get
                     else Stream.emit(i) }

  property("bracket (normal termination / failing)") = forAll {
  (s0: List[PureStream[Int]], f: Failure, ignoreFailure: Boolean) =>
    val c = new AtomicLong(0)
    val s = s0.map { s => Stream.bracket(Task.delay { c.decrementAndGet })(
      _ => if (ignoreFailure) s.stream else spuriousFail(s.stream, f),
      _ => Task.delay { c.incrementAndGet } )
    }
    try run { s.foldLeft(Stream.empty: Stream[Task,Int])(_ ++ _) }
    catch { case Err => () }
    f.tag |: 0L =? c.get
  }

  property("1 million brackets in sequence") = secure {
    val c = new AtomicLong(0)
    val b = Stream.bracket(Task.delay(c.incrementAndGet))(
      _ => Stream.emit(1),
      _ => Task.delay(c.decrementAndGet))
    val bs = List.fill(1000000)(b).foldLeft(Stream.empty: Stream[Task,Int])(_ ++ _)
    run { bs }
    c.get ?= 0
  }

  property("nested bracket") = forAll { (s0: List[Int], f: Failure) =>
    val c = new AtomicLong(0)
    // construct a deeply nested bracket stream in which the innermost stream fails
    // and check that as we unwind the stack, all resources get released
    val nested = Chunk.seq(s0).foldRight(f.get: Stream[Task,Int])((i,inner) =>
      Stream.bracket(Task.delay(c.decrementAndGet))(_ => inner, _ => Task.delay(c.incrementAndGet))
    )
    try { run { nested }; throw Err } // this test should always fail, so the `run` should throw
    catch { case Err => () }
    f.tag |: 0L =? c.get
  }

  case class Small(get: Long)
  implicit def smallLongArb = Arbitrary(Gen.choose(0L,10L).map(Small(_)))

  property("early termination of bracket") = forAll {
    (s: PureStream[Int], i: Small, j: Small, k: Small) =>
    val c = new AtomicLong(0)
    val bracketed = Stream.bracket(Task.delay(c.decrementAndGet))(
      _ => s.stream,
      _ => Task.delay(c.incrementAndGet))
    run { bracketed.take(i.get) } // early termination
    run { bracketed.take(i.get).take(j.get) } // 2 levels termination
    run { bracketed.take(i.get).take(i.get) } // 2 levels of early termination (2)
    run { bracketed.take(i.get).take(j.get).take(k.get) } // 3 levels of early termination
    // Let's just go crazy
    run { bracketed.take(i.get).take(j.get).take(i.get).take(k.get).take(j.get).take(i.get) }
    c.get ?= 0
  }

/*
asynchronous resource allocation
fail merge bracket
bracket merge fail
concurrent.join
using generated resources

asynchronous resource
*/
}
