package fs2

import fs2.internal.Token

/**
  * Signals interruption of the evaluation. Contains id of last scope that shall be interrupted and
  * any children of that scope.
  * @param scopeId
  */
final case class Interrupted(scopeId: Token) extends Throwable { override def fillInStackTrace = this }
