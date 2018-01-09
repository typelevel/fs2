package fs2

import scala.concurrent.duration._
import cats.effect.IO

import TestUtil._

class RetrySpec extends AsyncFs2Spec {
  case class RetryErr(msg: String = "") extends RuntimeException(msg)

  "retry" - {
    "immediate success" in {
      var attempts = 0
      def job = IO {
        attempts += 1
        "success"
      }


      runLogF(mkScheduler.flatMap(_.retry(job, 1.seconds, x => x, 100))).map { r =>
        attempts shouldBe 1
        r shouldBe Vector("success")
      }
    }

    "eventual success" in {
      var failures, successes = 0
      def job = IO {
        if (failures == 5) { successes += 1; "success" }
        else { failures += 1; throw RetryErr() }
      }


      runLogF(mkScheduler.flatMap(_.retry(job, 100.millis, x => x, 100))).map { r =>
        failures shouldBe 5
        successes shouldBe 1
        r shouldBe Vector("success")
      }
    }

    "maxRetries" in {
      var failures = 0
      def job = IO {
        failures += 1
        throw RetryErr(failures.toString)
      }

      runLogF(mkScheduler.flatMap(_.retry(job, 100.millis, x => x, 5))).failed.map {
        case RetryErr(msg) =>
          failures shouldBe 5
          msg shouldBe "5"
      }
    }

    "fatal" in {
      var failures, successes = 0
      def job = IO {
        if (failures == 5) { failures += 1; throw RetryErr("fatal") }
        else if (failures > 5) { successes += 1; "success" }
        else { failures += 1; throw RetryErr()}
      }

      val f: Throwable => Boolean = _.getMessage != "fatal"

      runLogF(mkScheduler.flatMap(_.retry(job, 100.millis, x => x, 100, f)))
        .failed
        .map {
          case RetryErr(msg) =>
            failures shouldBe 6
            successes shouldBe 0
            msg shouldBe "fatal"
        }
    }

    "delays" in {
      pending // Too finicky on Travis
      val delays = scala.collection.mutable.ListBuffer.empty[Long]
      val unit = 200
      val maxTries = 5
      def getDelays =
        delays.synchronized(delays.toList).sliding(2).map(s => (s.tail.head - s.head) / unit ).toList

      def job = {
        val start = System.currentTimeMillis()
        IO {
          delays.synchronized { delays += System.currentTimeMillis() - start }
          throw new Exception
        }
      }

      runLogF(mkScheduler.flatMap(_.retry(job, unit.millis, _ + unit.millis, maxTries)))
        .failed
        .map { r =>
          getDelays shouldBe List.range(1, maxTries)
        }
    }
  }
}
