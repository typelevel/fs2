package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._

class StreamAppSpec extends Fs2Spec {

  "StreamApp" - {
    /**
      * Simple Test Rig For Stream Apps
      * Takes the Stream that constitutes the Stream App
      * and observably cleans up when the process is stopped.
      */
    class TestStreamApp(stream: IO[Unit] => Stream[IO, ExitCode]) extends StreamApp[IO] {
      val cleanedUp = async.signalOf[IO,Boolean](false).unsafeRunSync

      override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] =
        stream(requestShutdown).onFinalize(cleanedUp.set(true))
    }

    "Terminate server on a failed stream" in {
      val testApp = new TestStreamApp(_ => Stream.raiseError(new Throwable("Bad Initial Process")))
      testApp.doMain(List.empty).unsafeRunSync shouldBe ExitCode.error
      testApp.cleanedUp.get.unsafeRunSync shouldBe true
    }

    "Terminate server on a valid stream" in {
      val testApp = new TestStreamApp(_ => Stream.emit(ExitCode.success))
      testApp.doMain(List.empty).unsafeRunSync shouldBe ExitCode.success
      testApp.cleanedUp.get.unsafeRunSync shouldBe true
    }

    "Terminate server on an empty stream" in {
      val testApp = new TestStreamApp(_ => Stream.empty)
      testApp.doMain(List.empty).unsafeRunSync shouldBe ExitCode.success
      testApp.cleanedUp.get.unsafeRunSync shouldBe true
    }

    "Terminate server with a specific exit code" in {
      val testApp = new TestStreamApp(_ => Stream.emit(ExitCode(42)))
      testApp.doMain(List.empty).unsafeRunSync shouldBe ExitCode(42)
      testApp.cleanedUp.get.unsafeRunSync shouldBe true
    }

    "Shut down a server from a separate thread" in {
      val requestShutdown = async.signalOf[IO,IO[Unit]](IO.unit).unsafeRunSync

      val testApp = new TestStreamApp(
        shutdown =>
          Stream.eval(requestShutdown.set(shutdown)) *>
            // run forever, emit nothing
            Stream.eval_(IO.async[Nothing] { _ =>
            }))

      (for {
        runApp <- async.start(testApp.doMain(List.empty))
        // Wait for app to start
        _ <- requestShutdown.discrete.takeWhile(_ == IO.unit).run
        // Run shutdown task
        _ <- requestShutdown.get.flatten
        result <- runApp
        cleanedUp <- testApp.cleanedUp.get
      } yield (result, cleanedUp)).unsafeRunTimed(5.seconds) shouldBe Some((ExitCode.success, true))
    }
  }
}
