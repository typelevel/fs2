package fs2

trait Trace {
  def enabled: Boolean
  def apply(msg: String): Unit
}

object Trace {
  object Stdout extends Trace {
    val enabled = true
    def apply(msg: String) = println(msg)
  }
  object Off extends Trace {
    val enabled = false
    def apply(msg: String) = ()
  }
}

