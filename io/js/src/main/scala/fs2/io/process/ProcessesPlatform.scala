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
import fs2.io.internal.MicrotaskExecutor
import fs2.io.internal.SuspendedStream
import fs2.io.internal.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[process] trait ProcessesCompanionPlatform {
  implicit def forAsync[F[_]](implicit F: Async[F]): Processes[F] = new UnsealedProcesses[F] {
    def spawn(process: ProcessBuilder): Resource[F, Process[F]] =
      Resource
        .make {
          F.async_[facade.child_process.ChildProcess] { cb =>
            val subprocess = facade.child_process.spawn(
              process.command,
              process.args.toJSArray,
              new facade.child_process.SpawnOptions {
                cwd = process.workingDirectory.fold[js.UndefOr[String]](js.undefined)(_.toString)
                env =
                  if (process.inheritEnv)
                    (facade.process.env ++ process.extraEnv).toJSDictionary
                  else
                    process.extraEnv.toJSDictionary
              }
            )

            subprocess.once("spawn", () => cb(Right(subprocess)))
            subprocess.once[js.Error]("error", e => cb(Left(js.JavaScriptException(e))))
          }
        } { childProcess =>
          F.asyncCheckAttempt[Unit] { cb =>
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
        }
        .flatMap { childProcess =>
          def suspend(readable: => fs2.io.Readable) =
            SuspendedStream(
              Stream.resource(fs2.io.suspendReadableAndRead()(readable)).flatMap(_._2)
            )

          (suspend(childProcess.stdout), suspend(childProcess.stderr)).mapN {
            (suspendedOut, suspendedErr) =>
              new UnsealedProcess[F] {

                def isAlive: F[Boolean] = F.delay {
                  (childProcess.exitCode eq null) && (childProcess.signalCode eq null)
                }

                def exitValue: F[Int] = F.asyncCheckAttempt[Int] { cb =>
                  F.delay {
                    (childProcess.exitCode: Any) match {
                      case i: Int => Right(i)
                      case _ =>
                        val f: js.Function1[Any, Unit] = {
                          case i: Int => cb(Right(i))
                          case _      => // do nothing
                        }
                        childProcess.once("exit", f)
                        Left(Some(F.delay(childProcess.removeListener("exit", f))))
                    }
                  }
                }

                def stdin: Pipe[F, Byte, Nothing] =
                  fs2.io.writeWritable(F.delay(childProcess.stdin))

                def stdout: Stream[F, Byte] = suspendedOut.stream

                def stderr: Stream[F, Byte] = suspendedErr.stream

              }

          }
        }
        .evalOn(MicrotaskExecutor) // guarantee that callbacks are setup before yielding to I/O

  }
}
