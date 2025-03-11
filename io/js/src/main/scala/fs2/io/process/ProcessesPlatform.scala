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
    def spawn(process: ProcessBuilder): Resource[F, Process[F]] =
      Resource {
        F.async_[(Process[F], F[Unit])] { cb =>
          

          val spawnOptions = js.Dynamic.literal {
              "cwd" -> process.workingDirectory.fold[js.UndefOr[String]](js.undefined)(_.toString)
              "env" ->(
                if (process.inheritEnv)
                  (facade.process.env ++ process.extraEnv).toJSDictionary
                else
                  process.extraEnv.toJSDictionary)
            }

          val childProcess = facade.child_process.spawn(
            process.command,
            process.args.toJSArray,
            spawnOptions.asInstanceOf[facade.child_process.SpawnOptions]
          )

          process.outputMode match {
            case ProcessOutputMode.Separate => // Default behavior
            case ProcessOutputMode.Merged   => spawnOptions.updateDynamic("stdio")("pipe")
            case ProcessOutputMode.FileOutput(path) =>
              spawnOptions.updateDynamic("stdio")(js.Array("pipe", path.toString, path.toString))
            case ProcessOutputMode.Inherit  => spawnOptions.updateDynamic("stdio")("inherit")
            case ProcessOutputMode.Ignore   => spawnOptions.updateDynamic("stdio")("ignore")
          }

          val fs2Process = new UnsealedProcess[F] {

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

            def stdin = writeWritable(F.delay(childProcess.stdin))

            def stdout = unsafeReadReadable(childProcess.stdout)

            def stderr = unsafeReadReadable(childProcess.stderr)
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
