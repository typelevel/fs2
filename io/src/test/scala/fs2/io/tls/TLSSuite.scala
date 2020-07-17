package fs2
package io
package tls

import cats.effect.{Blocker, IO}

abstract class TLSSuite extends Fs2Suite {
  def testTlsContext(blocker: Blocker): IO[TLSContext] =
    TLSContext.fromKeyStoreResource[IO](
      "keystore.jks",
      "password".toCharArray,
      "password".toCharArray,
      blocker
    )
}
