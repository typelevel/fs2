package fs2

import cats.~>
import org.scalactic._
import org.scalatest._
import org.scalatest.enablers._
import org.scalatest.prop._
import scala.concurrent.{ExecutionContext, Future}

class ToFuturePropCheckerAsserting[F[_]](toFuture: F ~> Future)(
    implicit val executionContext: ExecutionContext)
    extends PropCheckerAsserting[F[Assertion]] { self =>

  type Result = Future[Assertion]
  type S = F[Assertion]

  private val target = PropCheckerAsserting.assertingNatureOfFutureAssertion(executionContext)

  def discard(result: S): Boolean = ??? //target.discard(result)

  def succeed(result: S): (Boolean, Option[Throwable]) = ??? //target.succeed(result)

  def check1[A](fun: (A) => S,
                genA: Generator[A],
                prms: Configuration.Parameter,
                prettifier: Prettifier,
                pos: source.Position,
                names: List[String],
                argNames: Option[List[String]] = None): Result =
    target.check1((a: A) => toFuture(fun(a)), genA, prms, prettifier, pos, names, argNames)

  def check2[A, B](fun: (A, B) => S,
                   genA: Generator[A],
                   genB: Generator[B],
                   prms: Configuration.Parameter,
                   prettifier: Prettifier,
                   pos: source.Position,
                   names: List[String],
                   argNames: Option[List[String]] = None): Result =
    target.check2((a: A, b: B) => toFuture(fun(a, b)),
                  genA,
                  genB,
                  prms,
                  prettifier,
                  pos,
                  names,
                  argNames)

  def check3[A, B, C](fun: (A, B, C) => S,
                      genA: Generator[A],
                      genB: Generator[B],
                      genC: Generator[C],
                      prms: Configuration.Parameter,
                      prettifier: Prettifier,
                      pos: source.Position,
                      names: List[String],
                      argNames: Option[List[String]] = None): Result =
    target.check3((a: A, b: B, c: C) => toFuture(fun(a, b, c)),
                  genA,
                  genB,
                  genC,
                  prms,
                  prettifier,
                  pos,
                  names,
                  argNames)

  def check4[A, B, C, D](fun: (A, B, C, D) => S,
                         genA: org.scalatest.prop.Generator[A],
                         genB: org.scalatest.prop.Generator[B],
                         genC: org.scalatest.prop.Generator[C],
                         genD: org.scalatest.prop.Generator[D],
                         prms: Configuration.Parameter,
                         prettifier: Prettifier,
                         pos: source.Position,
                         names: List[String],
                         argNames: Option[List[String]] = None): Result =
    target.check4((a: A, b: B, c: C, d: D) => toFuture(fun(a, b, c, d)),
                  genA,
                  genB,
                  genC,
                  genD,
                  prms,
                  prettifier,
                  pos,
                  names,
                  argNames)

  def check5[A, B, C, D, E](fun: (A, B, C, D, E) => S,
                            genA: Generator[A],
                            genB: Generator[B],
                            genC: Generator[C],
                            genD: Generator[D],
                            genE: Generator[E],
                            prms: Configuration.Parameter,
                            prettifier: Prettifier,
                            pos: source.Position,
                            names: List[String],
                            argNames: Option[List[String]] = None): Result =
    target.check5((a: A, b: B, c: C, d: D, e: E) => toFuture(fun(a, b, c, d, e)),
                  genA,
                  genB,
                  genC,
                  genD,
                  genE,
                  prms,
                  prettifier,
                  pos,
                  names,
                  argNames)

  def check6[A, B, C, D, E, G](fun: (A, B, C, D, E, G) => S,
                               genA: Generator[A],
                               genB: Generator[B],
                               genC: Generator[C],
                               genD: Generator[D],
                               genE: Generator[E],
                               genG: Generator[G],
                               prms: Configuration.Parameter,
                               prettifier: Prettifier,
                               pos: source.Position,
                               names: List[String],
                               argNames: Option[List[String]] = None): Result =
    target.check6((a: A, b: B, c: C, d: D, e: E, g: G) => toFuture(fun(a, b, c, d, e, g)),
                  genA,
                  genB,
                  genC,
                  genD,
                  genE,
                  genG,
                  prms,
                  prettifier,
                  pos,
                  names,
                  argNames)
}
