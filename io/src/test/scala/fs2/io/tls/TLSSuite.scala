package fs2
package io
package tls

import cats.effect.IO

abstract class TLSSuite extends Fs2Suite {
  def testTlsContext: IO[TLSContext] =
    TLSContext.fromKeyStoreResource[IO](
      "keystore.jks",
      "password".toCharArray,
      "password".toCharArray
    )
}
