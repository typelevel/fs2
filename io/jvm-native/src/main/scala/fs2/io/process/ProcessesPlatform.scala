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
import cats.syntax.all.*
import fs2.io.CollectionCompat.*

import java.lang
import java.lang.ProcessBuilder.Redirect


private[process] trait ProcessesCompanionPlatform {
  def forAsync[F[_]](implicit F: Async[F]): Processes[F] = new UnsealedProcesses[F] {

    def spawn(process: ProcessBuilder): Resource[F, Process[F]] =
      Resource
        .make {
          F.blocking {
          val builder = new java.lang.ProcessBuilder((process.command :: process.args).asJava)

          process.workingDirectory.foreach { path =>
            builder.directory(path.toNioPath.toFile)
          }

          val env = builder.environment()
          if (!process.inheritEnv) env.clear()
          process.extraEnv.foreach { case (k, v) =>
            env.put(k, v)
          }

          process.outputMode match {
            case ProcessOutputMode.Separate => // Default behavior
            case ProcessOutputMode.Merged   => builder.redirectErrorStream(true)
            case ProcessOutputMode.FileOutput(path) =>
              builder.redirectOutput(Redirect.to(path.toNioPath.toFile))
            case ProcessOutputMode.Inherit  =>
              builder.redirectOutput(Redirect.INHERIT)
              builder.redirectError(Redirect.INHERIT)
            case ProcessOutputMode.Ignore   =>
              builder.redirectOutput(Redirect.DISCARD)
              builder.redirectError(Redirect.DISCARD)
          }

          builder.start()
        }
      } { process =>
        F.delay(process.isAlive())
          .ifM(
            F.blocking {
              process.destroy()
              process.waitFor()
              ()
            },
            F.unit
          )
      }
      .map { process =>
        new UnsealedProcess[F] {
          def isAlive = F.delay(process.isAlive())

            def exitValue = isAlive.ifM(
              evalOnVirtualThreadIfAvailable(F.interruptible(process.waitFor())),
              F.delay(process.exitValue())
            )

          def stdin = writeOutputStreamCancelable(
            F.delay(process.getOutputStream()),
            F.blocking(process.destroy())
          )

          def stdout = readInputStreamCancelable(
            F.delay(process.getInputStream()),
            F.blocking(process.destroy()),
            8192
          )

          def stderr = readInputStreamCancelable(
            F.delay(process.getErrorStream()),
            F.blocking(process.destroy()),
            8192
          )
        }
        }
  }
}
