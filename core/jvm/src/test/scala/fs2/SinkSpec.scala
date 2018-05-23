package fs2

import cats.effect.IO
import cats.implicits._
import fs2.async.Ref

import TestUtil._

class SinkSpec extends AsyncFs2Spec {
  "Sink" - {
    val s = Stream.emits(Seq(Left(1), Right("a"))).repeat.covary[IO]

    "either - does not drop elements" in {
      val is = Ref[IO, Vector[Int]](Vector.empty)
      val as = Ref[IO, Vector[String]](Vector.empty)

      val test = for {
        iref <- is
        aref <- as
        iSink = Sink((i: Int) => iref.modify(_ :+ i).void)
        aSink = Sink((a: String) => aref.modify(_ :+ a).void)
        eSink = Sink.either(left = iSink, right = aSink)
        _ <- s.take(10).to(eSink).compile.drain
        iResult <- iref.get
        aResult <- aref.get
      } yield {
        assert(iResult.length == 5)
        assert(aResult.length == 5)
      }

      test.unsafeToFuture
    }

    "either - termination" - {
      "left" in {
        val left: Sink[IO, Int] = _.take(0).void
        val right: Sink[IO, String] = _.void
        val sink = Sink.either(left, right)
        assert(runLog(s.through(sink)).length == 0)
      }

      "right" in {
        val left: Sink[IO, Int] = _.void
        val right: Sink[IO, String] = _.take(0).void
        val sink = Sink.either(left, right)
        assert(runLog(s.through(sink)).length == 0)
      }
    }
  }
}
