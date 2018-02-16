package fs2

import fs2.internal.{Algebra, CompileScope, FreeC}

final class StreamLeg[F[_], O](
    val head: Segment[O, Unit],
    private val scope: CompileScope[F, O],
    private val next: FreeC[Algebra[F, O, ?], Unit]
) {

  /**
    * Converts this leg back to regular stream. Scope is updated to one of this leg.
    * Note that when this is invoked, no more interleaving legs are allowed, and this must be very last
    * leg remaining.
    *
    *
    * Note that resulting stream won't contain the `head` of this leg.
    *
    * @return
    */
  def stream: Stream[F, O] =
    Stream.fromFreeC(Algebra.setScope(scope.id).flatMap { _ =>
      next
    })

  /** replaces head of this leg. usefull when head was not fully consumed **/
  def setHead(nextHead: Segment[O, Unit]): StreamLeg[F, O] =
    new StreamLeg[F, O](nextHead, scope, next)

  /** provides an uncons operation on leg of the stream **/
  def uncons: Pull[F, Nothing, Option[StreamLeg[F, O]]] =
    Pull
      .eval(Algebra.compileLoop(scope, next)(scope.F))
      .map { _.map { case (segment, scope, next) => new StreamLeg[F, O](segment, scope, next) } }

}
