package fs2

import scala.concurrent.duration._

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._

import munit.{FunSuite, TestOptions}

import fs2.concurrent._

class MemoryLeakSpec extends FunSuite {

  override def munitFlakyOK = true

  case class LeakTestParams(
      warmupIterations: Int = 3,
      samplePeriod: FiniteDuration = 1.seconds,
      monitorPeriod: FiniteDuration = 30.seconds,
      limitTotalBytesIncrease: Long = 20 * 1024 * 1024,
      limitConsecutiveIncreases: Int = 10
  )

  private def heapUsed: IO[Long] =
    IO {
      val runtime = Runtime.getRuntime
      runtime.gc()
      val total = runtime.totalMemory()
      val free = runtime.freeMemory()
      total - free
    }

  protected def leakTest[O](
      name: TestOptions,
      params: LeakTestParams = LeakTestParams()
  )(stream: => Stream[IO, O]): Unit = leakTestF(name, params)(stream.compile.drain)

  protected def leakTestF(
      name: TestOptions,
      params: LeakTestParams = LeakTestParams()
  )(f: => IO[Unit]): Unit =
    test(name) {
      println(s"Running leak test ${name.name}")
      IO.race(
        f,
        IO.race(
          monitorHeap(
            params.warmupIterations,
            params.samplePeriod,
            params.limitTotalBytesIncrease,
            params.limitConsecutiveIncreases
          ),
          IO.sleep(params.monitorPeriod)
        )
      ).map {
        case Left(_)         => ()
        case Right(Right(_)) => ()
        case Right(Left(path)) =>
          fail(s"leak detected - heap dump: $path")
      }.unsafeRunSync()
    }

  private def monitorHeap(
      warmupIterations: Int,
      samplePeriod: FiniteDuration,
      limitTotalBytesIncrease: Long,
      limitConsecutiveIncreases: Int
  ): IO[Path] = {
    def warmup(iterationsLeft: Int): IO[Path] =
      if (iterationsLeft > 0) IO.sleep(samplePeriod) >> warmup(iterationsLeft - 1)
      else heapUsed.flatMap(x => go(x, x, 0))

    def go(initial: Long, last: Long, positiveCount: Int): IO[Path] =
      IO.sleep(samplePeriod) >>
        heapUsed.flatMap { bytes =>
          val deltaSinceStart = bytes - initial
          val deltaSinceLast = bytes - last
          def printBytes(x: Long) = f"$x%,d"
          def printDelta(x: Long) = {
            val pfx = if (x > 0) "+" else ""
            s"$pfx${printBytes(x)}"
          }
          println(
            f"Heap: ${printBytes(bytes)}%12.12s total, ${printDelta(deltaSinceStart)}%12.12s since start, ${printDelta(deltaSinceLast)}%12.12s in last ${samplePeriod}"
          )
          if (deltaSinceStart > limitTotalBytesIncrease) dumpHeap
          else if (deltaSinceLast > 0)
            if (positiveCount > limitConsecutiveIncreases) dumpHeap
            else go(initial, bytes, positiveCount + 1)
          else go(initial, bytes, 0)
        }

    warmup(warmupIterations)
  }

  private def dumpHeap: IO[Path] =
    IO {
      val path = Files.createTempFile("fs2-leak-test-", ".hprof")
      Files.delete(path)
      val server = ManagementFactory.getPlatformMBeanServer
      val mbean = ManagementFactory.newPlatformMXBeanProxy(
        server,
        "com.sun.management:type=HotSpotDiagnostic",
        classOf[com.sun.management.HotSpotDiagnosticMXBean]
      )
      mbean.dumpHeap(path.toString, true)
      path
    }

  leakTest("groupWithin") {
    Stream
      .eval(IO.never)
      .covary[IO]
      .groupWithin(Int.MaxValue, 1.millis)
  }

  leakTest("topic continuous publish") {
    Stream
      .eval(Topic[IO, Int](-1))
      .flatMap(topic => Stream.repeatEval(topic.publish1(1)))
  }

  leakTest("brackets") {
    Stream.constant(1).flatMap { _ =>
      Stream.bracket(IO.unit)(_ => IO.unit).flatMap(_ => Stream.emits(List(1, 2, 3)))
    }
  }

  leakTest("repeatPull") {
    def id[F[_], A]: Pipe[F, A, A] =
      _.repeatPull {
        _.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h).as(Some(t))
          case None         => Pull.pure(None)
        }
      }
    Stream.constant(1).covary[IO].through(id[IO, Int])
  }

  leakTest("repeatEval") {
    def id[F[_], A]: Pipe[F, A, A] = {
      def go(s: Stream[F, A]): Pull[F, A, Unit] =
        s.pull.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h) >> go(t); case None => Pull.done
        }
      in => go(in).stream
    }
    Stream.repeatEval(IO(1)).through(id[IO, Int])
  }

  leakTest("append") {
    (Stream.constant(1).covary[IO] ++ Stream.empty).pull.echo.stream
  }

  // TODO This runs and after 30s, fails to cancel
  leakTest("drain onComplete".ignore) {
    val s = Stream.repeatEval(IO(1)).pull.echo.stream.drain ++ Stream.exec(IO.unit)
    Stream.empty.covary[IO].merge(s)
  }

  leakTest("parJoin") {
    Stream.constant(Stream.empty[IO]).parJoin(5)
  }

  leakTest("dangling dequeue") {
    Stream
      .eval(Queue.unbounded[IO, Int])
      .flatMap(q => Stream.constant(1).flatMap(_ => Stream.empty.mergeHaltBoth(q.dequeue)))
  }

  leakTest("awakeEvery") {
    Stream.awakeEvery[IO](1.millis).flatMap(_ => Stream.eval(IO.unit))
  }

  leakTest("signal discrete") {
    Stream
      .eval(SignallingRef[IO, Unit](()))
      .flatMap(signal => signal.discrete.evalMap(a => signal.set(a)))
  }

  leakTest("signal continuous") {
    Stream
      .eval(SignallingRef[IO, Unit](()))
      .flatMap(signal => signal.continuous.evalMap(a => signal.set(a)))
  }

  leakTest("constant eval") {
    var cnt = 0
    var start = System.currentTimeMillis
    Stream
      .constant(())
      .flatMap { _ =>
        Stream.eval(IO {
          cnt = (cnt + 1) % 1000000
          if (cnt == 0) {
            val now = System.currentTimeMillis
            start = now
          }
        })
      }
  }

  leakTest("recursive flatMap") {
    def loop: Stream[IO, Unit] = Stream(()).covary[IO].flatMap(_ => loop)
    loop
  }

  leakTest("eval + flatMap + map") {
    Stream
      .eval(IO.unit)
      .flatMap(_ => Stream.emits(Seq()))
      .map(x => x)
      .repeat
  }

  leakTest("queue") {
    Stream
      .eval(Queue.bounded[IO, Either[Throwable, Option[Int]]](10))
      .flatMap { queue =>
        queue
          .dequeueChunk(Int.MaxValue)
          .rethrow
          .unNoneTerminate
          .concurrently(
            Stream
              .constant(1, 128)
              .covary[IO]
              .noneTerminate
              .attempt
              .evalMap(queue.enqueue1(_))
          )
          .evalMap(_ => IO.unit)
      }
  }

  leakTest("progress merge") {
    val progress = Stream.constant(1, 128).covary[IO]
    progress.merge(progress)
  }

  leakTest("hung merge") {
    val hung = Stream.eval(IO.never)
    val progress = Stream.constant(1, 128).covary[IO]
    hung.merge(progress)
  }

  leakTest("zip + flatMap + parJoin") {
    val sources: Stream[IO, Stream[IO, Int]] = Stream(Stream.empty).repeat
    Stream
      .fixedDelay[IO](1.milliseconds)
      .zip(sources)
      .flatMap { case (_, s) =>
        s.map(Stream.constant(_).covary[IO]).parJoinUnbounded
      }
  }

  leakTest("retry") {
    Stream.retry(IO.unit, 1.second, _ * 2, 10).repeat
  }

  leakTest("attempts + pull") {
    Stream.eval(IO.unit).attempts(Stream.constant(1.second)).head.repeat
  }
}
