package fs2

import java.util.concurrent.atomic.AtomicInteger
import cats.effect.IO

class StreamPerformanceSpec extends Fs2Spec {

  "Stream Performance" - {

    import Stream._

    case object FailWhale extends RuntimeException("the system... is down")

    val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)

    "left-associated ++" - { Ns.foreach { N =>
      N.toString in {
        runLog((1 until N).map(emit).foldLeft(emit(0))(_ ++ _)) shouldBe Vector.range(0,N)
      }
    }}

    "right-associated ++" - { Ns.foreach { N =>
      N.toString in {
        runLog((0 until N).map(emit).foldRight(Stream.empty: Stream[Pure,Int])(_ ++ _)) shouldBe Vector.range(0,N)
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
        runLog((chunk(Chunk.seq(0 until N)): Stream[IO,Int]).repeatPull {
          _.uncons1.flatMap { case None => Pull.pure(None); case Some((hd,tl)) => Pull.output1(hd).as(Some(tl)) }
        }) shouldBe Vector.range(0,N)
      }
    }}

    "bracket + handleErrorWith (1)" - { Ns.foreach { N =>
      N.toString in {
        val open = new AtomicInteger(0)
        val ok = new AtomicInteger(0)
        val bracketed = bracket(IO { open.incrementAndGet })(
          _ => emit(1) ++ Stream.raiseError(FailWhale),
          _ => IO { ok.incrementAndGet; open.decrementAndGet; () }
        )
        // left-associative handleErrorWith chains
        assert(throws (FailWhale) {
          List.fill(N)(bracketed).foldLeft(Stream.raiseError(FailWhale): Stream[IO,Int]) {
            (acc,hd) => acc handleErrorWith { _ => hd }
          }
        })
        ok.get shouldBe N
        open.get shouldBe 0
        ok.set(0)
        // right-associative handleErrorWith chains
        assert(throws (FailWhale) {
          List.fill(N)(bracketed).foldLeft(Stream.raiseError(FailWhale): Stream[IO,Int]) {
            (tl,hd) => hd handleErrorWith { _ => tl }
          }
        })
        ok.get shouldBe N
        open.get shouldBe 0
      }
    }}

    "chunky flatMap" - { Ns.take(9).foreach { N =>
      N.toString in {
        runLog(emits(Vector.range(0,N)).flatMap(i => emit(i))) shouldBe Vector.range(0,N)
      }
    }}
  }
}
