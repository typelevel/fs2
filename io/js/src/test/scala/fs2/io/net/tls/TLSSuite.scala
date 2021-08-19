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
package net
package tls

import cats.effect.IO
import cats.syntax.all._
import scala.scalajs.js
import scala.scalajs.js.annotation._
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.fsPromisesMod

import scala.annotation.nowarn

abstract class TLSSuite extends Fs2Suite {
  def testTlsContext: IO[TLSContext[IO]] = IO
    .fromPromise(
      IO(fsPromisesMod.readFile("io/shared/src/test/resources/keystore.jks"))
    )
    .map(JKS.toPem(_, "password")("server"))
    .map { certKey =>
      Network[IO].tlsContext.fromSecureContext(
        SecureContext(
          ca = List(certKey.cert.asRight).some,
          cert = List(certKey.cert.asRight).some,
          key = List(SecureContext.Key(certKey.key.asRight, "password".some)).some
        )
      )
    }

  val logger = TLSLogger.Disabled
  // val logger = TLSLogger.Enabled(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))

}

@js.native
@JSImport("jks-js", JSImport.Namespace)
object JKS extends js.Any {

  @js.native
  trait CertKey extends js.Any {
    def cert: String = js.native
    def key: String = js.native
  }

  @nowarn("cat=unused-params")
  def toPem(buffer: bufferMod.global.Buffer, password: String): js.Dictionary[CertKey] = js.native

}
