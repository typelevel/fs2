package fs2.io.tls

final case class TLSSessionConfig(
    needClientAuth: Boolean = false,
    wantClientAuth: Boolean = false,
    enableSessionCreation: Boolean = true,
    enabledCipherSuites: Option[List[String]] = None,
    enabledProtocols: Option[List[String]] = None
)
