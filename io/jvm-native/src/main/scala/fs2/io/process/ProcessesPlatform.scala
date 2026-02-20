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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import fs2.io.CollectionCompat.*

import java.lang

private[process] trait ProcessesCompanionPlatform {
  def forAsync[F[_]](implicit F: Async[F]): Processes[F] = new UnsealedProcesses[F] {

    def spawn(process: ProcessBuilder): Resource[F, Process[F]] =
      Resource
        .make {
          F.blocking {
            val builder = new lang.ProcessBuilder((process.command :: process.args).asJava)

            process.workingDirectory.foreach { path =>
              builder.directory(path.toNioPath.toFile)
            }

            val env = builder.environment()
            if (!process.inheritEnv) env.clear()
            process.extraEnv.foreach { case (k, v) =>
              env.put(k, v)
            }

            def toJavaRedirect(redirect: Redirect): lang.ProcessBuilder.Redirect =
              redirect match {
                case Redirect.Pipe    => lang.ProcessBuilder.Redirect.PIPE
                case Redirect.Inherit => lang.ProcessBuilder.Redirect.INHERIT
                case Redirect.Discard =>
                  val devNull =
                    if (System.getProperty("os.name").toLowerCase.contains("windows")) "NUL"
                    else "/dev/null"
                  lang.ProcessBuilder.Redirect.to(new java.io.File(devNull))
                case Redirect.FromPath(path) =>
                  lang.ProcessBuilder.Redirect.from(path.toNioPath.toFile)
                case Redirect.ToPath(path, append) =>
                  if (append) lang.ProcessBuilder.Redirect.appendTo(path.toNioPath.toFile)
                  else lang.ProcessBuilder.Redirect.to(path.toNioPath.toFile)
              }

            builder.redirectInput(toJavaRedirect(process.stdin))
            builder.redirectOutput(toJavaRedirect(process.stdout))
            builder.redirectError(toJavaRedirect(process.stderr))
            builder.redirectErrorStream(process.redirectErrorStream)

            builder.start()
          }
        } { process =>
          F.delay(process.isAlive())
            .ifM(
              evalOnVirtualThreadIfAvailable(
                F.blocking {
                  process.destroy()
                  process.waitFor()
                  ()
                }
              ),
              F.unit
            )
        }
        .map { jProcess =>
          new UnsealedProcess[F] {
            def isAlive = F.delay(jProcess.isAlive())

            def exitValue = isAlive.ifM(
              evalOnVirtualThreadIfAvailable(F.interruptible(jProcess.waitFor())),
              F.delay(jProcess.exitValue())
            )

            def stdin =
              if (process.stdin == Redirect.Pipe)
                writeOutputStreamCancelable(
                  F.delay(jProcess.getOutputStream()),
                  F.blocking(jProcess.destroy())
                )
              else _.drain

            def stdout =
              if (process.stdout == Redirect.Pipe)
                readInputStreamCancelable(
                  F.delay(jProcess.getInputStream()),
                  F.blocking(jProcess.destroy()),
                  8192
                )
              else Stream.empty

            def stderr =
              if (process.stderr == Redirect.Pipe && !process.redirectErrorStream)
                readInputStreamCancelable(
                  F.delay(jProcess.getErrorStream()),
                  F.blocking(jProcess.destroy()),
                  8192
                )
              else Stream.empty

          }
        }
  }
}
