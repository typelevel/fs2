package fs2

import cats.effect.IO
import cats.implicits._

class StreamPerformanceSpec extends Fs2Spec {

  "Stream Performance" - {

    val Ns = List(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)

    "left-associated ++" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N).map(Stream.emit).foldLeft(Stream.emit(0))(_ ++ _).toVector shouldBe Vector
            .range(0, N)
        }
      }
    }

    "right-associated ++" - {
      Ns.foreach { N =>
        N.toString in {
          (0 until N)
            .map(Stream.emit)
            .foldRight(Stream.empty: Stream[Pure, Int])(_ ++ _)
            .toVector shouldBe Vector.range(0, N)
        }
      }
    }

    "left-associated flatMap 1" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0))((acc, a) => acc.flatMap(_ => a))
            .toVector shouldBe Vector(N - 1)
        }
      }
    }

    "left-associated map 1" - {
      Ns.foreach { N =>
        N.toString in {
          pending
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0))((acc, a) => acc.map(_ + 1))
            .toVector shouldBe Vector(N - 1)
        }
      }
    }

    "left-associated eval() ++ flatMap 1" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0).covary[IO])((acc, a) =>
              acc.flatMap(_ => Stream.eval(IO.unit).flatMap(_ => a)))
            .compile
            .toVector
            .unsafeRunSync shouldBe Vector(N - 1)
        }
      }
    }

    "right-associated flatMap 1" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0))((acc, a) => a.flatMap(_ => acc))
            .toVector shouldBe Vector(0)
        }
      }
    }

    "right-associated eval() ++ flatMap 1" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0).covary[IO])((acc, a) =>
              a.flatMap(_ => Stream.eval(IO.unit).flatMap(_ => acc)))
            .compile
            .toVector
            .unsafeRunSync shouldBe Vector(0)
        }
      }
    }

    "left-associated flatMap 2" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0) ++ Stream.emit(1) ++ Stream.emit(2))((acc, a) =>
              acc.flatMap(_ => a))
            .toVector shouldBe Vector(N - 1, N - 1, N - 1)
        }
      }
    }

    "right-associated flatMap 2" - {
      Ns.foreach { N =>
        N.toString in {
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0) ++ Stream.emit(1) ++ Stream.emit(2))((acc, a) =>
              a.flatMap(_ => acc))
            .toVector shouldBe Vector(0, 1, 2)
        }
      }
    }

    "transduce (id)" - {
      Ns.foreach { N =>
        N.toString in {
          (Stream
            .chunk(Chunk.seq(0 until N)))
            .repeatPull {
              _.uncons1.flatMap {
                case None           => Pull.pure(None)
                case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
              }
            }
            .toVector shouldBe Vector.range(0, N)
        }
      }
    }

    "bracket + handleErrorWith" - {
      "left associated" - {
        Ns.foreach { N =>
          N.toString in {
            Counter[IO].flatMap { open =>
              Counter[IO].flatMap { ok =>
                val bracketed: Stream[IO, Int] = Stream
                  .bracket(open.increment)(_ => ok.increment >> open.decrement)
                  .flatMap(_ => Stream(1) ++ Stream.raiseError[IO](new Err))
                val s: Stream[IO, Int] =
                  List
                    .fill(N)(bracketed)
                    .foldLeft(Stream.raiseError[IO](new Err): Stream[IO, Int]) { (acc, hd) =>
                      acc.handleErrorWith(_ => hd)
                    }
                s.compile.toList
                  .assertThrows[Err]
                  .flatMap(_ => (ok.get, open.get).tupled)
                  .asserting {
                    case (ok, open) =>
                      ok shouldBe N
                      open shouldBe 0
                  }
              }
            }
          }
        }
      }

      "right associated" - {
        Ns.foreach { N =>
          N.toString in {
            Counter[IO].flatMap { open =>
              Counter[IO].flatMap { ok =>
                val bracketed: Stream[IO, Int] = Stream
                  .bracket(open.increment)(_ => ok.increment >> open.decrement)
                  .flatMap(_ => Stream(1) ++ Stream.raiseError[IO](new Err))
                val s: Stream[IO, Int] = List
                  .fill(N)(bracketed)
                  .foldLeft(Stream.raiseError[IO](new Err): Stream[IO, Int]) { (tl, hd) =>
                    hd.handleErrorWith(_ => tl)
                  }
                s.compile.toList
                  .assertThrows[Err]
                  .flatMap(_ => (ok.get, open.get).tupled)
                  .asserting {
                    case (ok, open) =>
                      ok shouldBe N
                      open shouldBe 0
                  }
              }
            }
          }
        }
      }
    }

    "chunky flatMap" - {
      Ns.foreach { N =>
        N.toString in {
          Stream.emits(Vector.range(0, N)).flatMap(i => Stream.emit(i)).toVector shouldBe Vector
            .range(0, N)
        }
      }
    }

    "chunky map with uncons" - {
      Ns.foreach { N =>
        N.toString in {
          Stream
            .emits(Vector.range(0, N))
            .map(i => i)
            .chunks
            .flatMap(Stream.chunk(_))
            .toVector shouldBe Vector.range(0, N)
        }
      }
    }
  }
}
