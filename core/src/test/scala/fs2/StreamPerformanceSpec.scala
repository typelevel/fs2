package fs2

import java.util.concurrent.atomic.AtomicInteger
import fs2.util._

class StreamPerformanceSpec extends Fs2Spec {

  "Stream Performance" - {

    import Stream._

    case object FailWhale extends RuntimeException("the system... is down")

    val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)

    def ranges(N: Int): List[Stream[Pure,Int]] = List(
      // left associated ++
      (1 until N).map(emit).foldLeft(emit(0))(_ ++ _),
      // right associated ++
      Chunk.seq((0 until N) map emit).foldRight(Stream.empty: Stream[Pure,Int])(_ ++ _)
    )

    "left-associated ++" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).foldLeft(emit(0))(_ ++ _)) shouldBe Vector.range(0,N)
      }
    }}

    "right-associated ++" - { Ns.foreach { N =>
      N.toString in {
        runLog(Chunk.seq((0 until N).map(emit)).foldRight(Stream.empty: Stream[Pure,Int])(_ ++ _)) shouldBe Vector.range(0,N)
      }
    }}

    "left-associated flatMap 1" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).foldLeft(emit(0))((acc,a) => acc flatMap { _ => a })) shouldBe Vector(N-1)
      }
    }}

    "right-associated flatMap 1" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).reverse.foldLeft(emit(0))((acc,a) => a flatMap { _ => acc })) shouldBe Vector(0)
      }
    }}

    "left-associated flatMap 2" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).foldLeft(emit(0) ++ emit(1) ++ emit(2))(
          (acc,a) => acc flatMap { _ => a })) shouldBe Vector(N-1, N-1, N-1)
      }
    }}

    "right-associated flatMap 2" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).reverse.foldLeft(emit(0) ++ emit(1) ++ emit(2))(
          (acc,a) => a flatMap { _ => acc })) shouldBe Vector(0,1,2)
      }
    }}

    "transduce (id)" - { Ns.foreach { N =>
      N.toString in {
        runLog((chunk(Chunk.seq(0 until N)): Stream[Task,Int]).repeatPull { (s: Handle[Task,Int]) =>
          for {
            s2 <- s.await1
            _ <- Pull.output1(s2.head)
          } yield s2.tail
        }) shouldBe Vector.range(0,N)
      }
    }}

    "bracket + onError (1)" - { Ns.foreach { N =>
      N.toString in {
        val open = new AtomicInteger(0)
        val ok = new AtomicInteger(0)
        val bracketed = bracket(Task.delay { open.incrementAndGet })(
          _ => emit(1) ++ Stream.fail(FailWhale),
          _ => Task.delay { ok.incrementAndGet; open.decrementAndGet; () }
        )
        // left-associative onError chains
        assert(throws (FailWhale) {
          List.fill(N)(bracketed).foldLeft(Stream.fail(FailWhale): Stream[Task,Int]) {
            (acc,hd) => acc onError { _ => hd }
          }
        })
        ok.get shouldBe N
        open.get shouldBe 0
        ok.set(0)
        // right-associative onError chains
        assert(throws (FailWhale) {
          List.fill(N)(bracketed).foldLeft(Stream.fail(FailWhale): Stream[Task,Int]) {
            (tl,hd) => hd onError { _ => tl }
          }
        })
        ok.get shouldBe N
        open.get shouldBe 0
      }
    }
  }}
}
