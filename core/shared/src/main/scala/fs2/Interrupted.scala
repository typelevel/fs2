package fs2

import fs2.internal.Token

/**
  * Signals interruption of the evaluation. Contains id of last scope that shall be interrupted and
  * any children of that scope.
  * @param scopeId  Id of the scope that shall be the last interrupted by this message
  * @param loop     In case of infinite recursion this prevents interpreter to search for cleanups indefinitely.
  *                 In each recursive iteration, this will increment by 1 up to limit defined in current scope,
  *                 After which this will Interrupt stream w/o searching further for any cleanups.
  */
final case class Interrupted(scopeId: Token, loop: Int) extends Throwable {
  override def fillInStackTrace = this

  override def toString = s"Interrupted($scopeId, $loop)"
}

