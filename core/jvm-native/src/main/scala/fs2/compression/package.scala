package fs2
package object compression {
  type MTime = java.time.Instant

  object MTime {
    def fromEpochSeconds(e: Long): Option[MTime] = Some(java.time.Instant.ofEpochSecond(e.toLong))
  }
}
