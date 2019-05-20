package fs2
package io
package file

import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._
import java.nio.file._

class WatcherSpec extends BaseFileSpec {
  "Watcher" - {
    "supports watching a file" - {
      "for modifications" in {
        tempFile
          .flatMap { f =>
            file
              .watch[IO](f, modifiers = modifiers)
              .takeWhile({
                case Watcher.Event.Modified(f, _) => false; case _ => true
              }, true)
              .concurrently(smallDelay ++ modify(f))
          }
          .compile
          .drain
          .assertNoException
      }
      "for deletions" in {
        tempFile
          .flatMap { f =>
            file
              .watch[IO](f, modifiers = modifiers)
              .takeWhile({
                case Watcher.Event.Deleted(f, _) => false; case _ => true
              }, true)
              .concurrently(smallDelay ++ Stream.eval(IO(Files.delete(f))))
          }
          .compile
          .drain
          .assertNoException
      }
    }

    "supports watching a directory" - {
      "static recursive watching" in {
        tempDirectory
          .flatMap { dir =>
            val a = dir.resolve("a")
            val b = a.resolve("b")
            Stream.eval(IO(Files.createDirectory(a)) >> IO(Files.write(b, Array[Byte]()))) >>
              (file
                .watch[IO](dir, modifiers = modifiers)
                .takeWhile({
                  case Watcher.Event.Modified(b, _) => false; case _ => true
                })
                .concurrently(smallDelay ++ modify(b)))
          }
          .compile
          .drain
          .assertNoException
      }
      "dynamic recursive watching" in {
        tempDirectory
          .flatMap { dir =>
            val a = dir.resolve("a")
            val b = a.resolve("b")
            file
              .watch[IO](dir, modifiers = modifiers)
              .takeWhile({
                case Watcher.Event.Created(b, _) => false; case _ => true
              })
              .concurrently(smallDelay ++ Stream.eval(
                IO(Files.createDirectory(a)) >> IO(Files.write(b, Array[Byte]()))))
          }
          .compile
          .drain
          .assertNoException
      }
    }
  }

  private def smallDelay: Stream[IO, Nothing] =
    Stream.sleep_[IO](1000.millis)

  // Tries to load the Oracle specific SensitivityWatchEventModifier to increase sensitivity of polling
  private val modifiers: Seq[WatchEvent.Modifier] = {
    try {
      val c = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
      Seq(c.getField("HIGH").get(c).asInstanceOf[WatchEvent.Modifier])
    } catch {
      case t: Throwable => Nil
    }
  }
}
