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
import cats.effect.kernel.Sync
import cats.syntax.all._
import fs2.io.CollectionCompat._

import java.lang

private[process] trait ProcessSpawnCompanionPlatform {
  implicit def forSync[F[_]](implicit F: Sync[F]): ProcessSpawn[F] = new UnsealedProcessSpawn[F] {
    def spawn(process: ProcessBuilder): Resource[F, Process[F]] =
      Resource
        .make {
          F.blocking {
            val builder = new lang.ProcessBuilder((process.command :: process.args).asJava)

            process.workingDirectory.foreach { path =>
              builder.directory(path.toNioPath.toFile)
            }

            val env = builder.environment()
            process.env.foreach { case (k, v) =>
              env.put(k, v)
            }

            builder.start()
          }
        } { process =>
          F.delay(process.isAlive())
            .ifM(
              F.blocking {
                process.destroyForcibly()
                process.waitFor()
                ()
              },
              F.unit
            )
        }
        .map { process =>
          new UnsealedProcess[F] {
            def isAlive = F.delay(process.isAlive())
            def exitValue = F.interruptible(process.waitFor())
            def stdin = writeOutputStream(F.delay(process.getOutputStream()))
            def stdout = readInputStream(F.delay(process.getInputStream()), 8192, false)
            def stderr = readInputStream(F.delay(process.getErrorStream()), 8192, false)
          }
        }
  }
}
