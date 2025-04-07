package fs2
package io.net.unixsocket

import cats.effect.IO

trait UnixSocketsSuitePlatform { self: UnixSocketsSuite =>
  if (JdkUnixSockets.supported) {
    testProvider("jdk")(JdkUnixSockets.forAsync[IO])
    testPeerCred("jdk")(JdkUnixSockets.forAsync[IO])
  }
  if (JnrUnixSockets.supported) {
    testProvider("jnr")(JnrUnixSockets.forAsync[IO])
    testPeerCred("jnr")(JnrUnixSockets.forAsync[IO])
  }
} 