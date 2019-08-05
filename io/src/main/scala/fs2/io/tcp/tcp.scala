package fs2
package io

import java.net.SocketOption

/** Provides support for TCP networking. */
package object tcp {
  type SocketMapping[A] = (SocketOption[A], A)
}
