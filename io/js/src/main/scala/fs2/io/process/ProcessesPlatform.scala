/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package process

import cats.effect.kernel.Resource
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.io.internal.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[process] trait ProcessesCompanionPlatform {
  def forAsync[F[_]](implicit F: Async[F]): Processes[F] = new UnsealedProcesses[F] {
    def spawn(process: ProcessBuilder): Resource[F, Process[F]] = {

      def open(redirect: Redirect, flags: String): Resource[F, js.Any] =
        redirect match {
          case Redirect.Pipe    => Resource.pure("pipe")
          case Redirect.Inherit => Resource.pure("inherit")
          case Redirect.Discard => Resource.pure("ignore")
          case Redirect.FromPath(path) =>
            Resource.make(F.delay(facade.fs.openSync(path.toString, flags)))(fd =>
              F.delay(facade.fs.closeSync(fd))
            ).map(_.asInstanceOf[js.Any])
          case Redirect.ToPath(path, append) =>
            val f = if (append) "a" else "w"
            Resource.make(F.delay(facade.fs.openSync(path.toString, f)))(fd =>
              F.delay(facade.fs.closeSync(fd))
            ).map(_.asInstanceOf[js.Any])
        }

      (
        open(process.stdin, "r"),
        open(process.stdout, "w"),
        open(process.stderr, "w")
      ).tupled.flatMap { case (stdinO, stdoutO, stderrO) =>
        Resource {
          F.async_[(Process[F], F[Unit])] { cb =>
            val childProcess = facade.child_process.spawn(
              process.command,
              process.args.toJSArray,
              new facade.child_process.SpawnOptions {
                cwd = process.workingDirectory.fold[js.UndefOr[String]](js.undefined)(_.toString)
                env =
                  if (process.inheritEnv)
                    (facade.process.env ++ process.extraEnv).toJSDictionary
                  else
                    process.extraEnv.toJSDictionary
                stdio = js.Array(
                  stdinO,
                  stdoutO,
                  if (process.redirectErrorStream) stdoutO else stderrO
                )
              }
            )

            val fs2Process = new UnsealedProcess[F] {

              def isAlive: F[Boolean] = F.delay {
                (childProcess.exitCode eq null) && (childProcess.signalCode eq null)
              }

              def exitValue: F[Int] = F.asyncCheckAttempt[Int] { cb =>
                F.delay {
                  (childProcess.exitCode: Any) match {
                    case i: Int => Right(i)
                    case _      =>
                      val f: js.Function1[Any, Unit] = {
                        case i: Int => cb(Right(i))
                        case _      => // do nothing
                      }
                      childProcess.once("exit", f)
                      Left(Some(F.delay(childProcess.removeListener("exit", f))))
                  }
                }
              }

              def stdin = childProcess.stdin match {
                case null => _.drain
                case s    => writeWritable(F.delay(s))
              }

              def stdout = {
                val out = childProcess.stdout match {
                  case null => Stream.empty
                  case s    => unsafeReadReadable(s)
                }
                if (process.redirectErrorStream) {
                  val err = childProcess.stderr match {
                    case null => Stream.empty
                    case s    => unsafeReadReadable(s)
                  }
                  out.merge(err)
                } else out
              }

              def stderr = childProcess.stderr match {
                case null => Stream.empty
                case s    => if (process.redirectErrorStream) Stream.empty else unsafeReadReadable(s)
              }
            }

            val finalize = F.asyncCheckAttempt[Unit] { cb =>
              F.delay {
                if ((childProcess.exitCode ne null) || (childProcess.signalCode ne null)) {
                  Either.unit
                } else {
                  childProcess.kill()
                  childProcess.once("exit", () => cb(Either.unit))
                  Left(None)
                }
              }
            }

            childProcess.once("spawn", () => cb(Right(fs2Process -> finalize)))
            childProcess.once[js.Error]("error", e => cb(Left(js.JavaScriptException(e))))
          }
        }
      }
    }
  }
}
