package fs2

final case class ExitCode(code: Byte)

object ExitCode {
  def fromInt(code: Int): ExitCode = ExitCode(code.toByte)
  val success: ExitCode = ExitCode(0)
  val error: ExitCode = ExitCode(1)
}
