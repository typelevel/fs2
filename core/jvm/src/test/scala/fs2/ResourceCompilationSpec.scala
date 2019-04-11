package fs2

import cats.implicits._
import cats.effect.{ExitCase, IO, Resource}
import cats.effect.concurrent.{Deferred, Ref}
import scala.concurrent.duration._

class ResourceCompilationSpec extends AsyncFs2Spec {
  "compile.resource - concurrently" in {
    val prog: Resource[IO, IO[Unit]] =
      Stream
        .eval(Deferred[IO, Unit].product(Deferred[IO, Unit]))
        .flatMap {
          case (startCondition, waitForStream) =>
            val worker = Stream.eval(startCondition.get) ++ Stream.eval(waitForStream.complete(()))
            val result = startCondition.complete(()) >> waitForStream.get

            Stream.emit(result).concurrently(worker)
        }
        .compile
        .resource
        .lastOrError

    prog.use(x => x).timeout(5.seconds).unsafeToFuture
  }

  "compile.resource - onFinalise" in {
    val expected = List(
      "stream - start",
      "stream - done",
      "io - done",
      "io - start",
      "resource - start",
      "resource - done"
    )

    Ref[IO]
      .of(List.empty[String])
      .flatMap { st =>
        def record(s: String): IO[Unit] = st.update(_ :+ s)

        def stream =
          Stream
            .emit("stream - start")
            .onFinalize(record("stream - done"))
            .evalMap(x => record(x))
            .compile
            .lastOrError

        def io =
          Stream
            .emit("io - start")
            .onFinalize(record("io - done"))
            .compile
            .lastOrError
            .flatMap(x => record(x))

        def resource =
          Stream
            .emit("resource - start")
            .onFinalize(record("resource - done"))
            .compile
            .resource
            .lastOrError
            .use(x => record(x))

        stream >> io >> resource >> st.get
      }
      .unsafeToFuture
      .map(_ shouldBe expected)
  }

  "compile.resource - allocated" in {
    Ref[IO]
      .of(false)
      .flatMap { written =>
        Stream
          .emit(())
          .onFinalize(written.set(true))
          .compile
          .resource
          .lastOrError
          .allocated >> written.get
      }
      .unsafeToFuture
      .map(written => written shouldBe false)
  }

  "compile.resource - interruption (1)" in {
    val s = Stream
      .resource {
        Stream.never[IO].compile.resource.drain
      }
      .interruptAfter(200.millis)
      .drain ++ Stream.emit(true)

    s.compile.lastOrError
      .timeout(2.seconds)
      .unsafeToFuture
      .map(_ shouldBe true)
  }

  "compile.resource - interruption (2)" in {
    val p = (Deferred[IO, ExitCase[Throwable]]).flatMap { stop =>
      val r = Stream
        .never[IO]
        .compile
        .resource
        .drain
        .use { _ =>
          IO.unit
        }
        .guaranteeCase(stop.complete)

      r.start.flatMap { fiber =>
        IO.sleep(200.millis) >> fiber.cancel >> stop.get
      }
    }

    p.timeout(2.seconds)
      .unsafeToFuture
      .map(_ shouldBe ExitCase.Canceled)
  }
}

object ResourceCompilationSpec {

  /** This should compile */
  val pure: List[Int] = Stream.range(0, 5).compile.toList
  val io: IO[List[Int]] = Stream.range(0, 5).covary[IO].compile.toList
  val resource: Resource[IO, List[Int]] = Stream.range(0, 5).covary[IO].compile.resource.toList
}
