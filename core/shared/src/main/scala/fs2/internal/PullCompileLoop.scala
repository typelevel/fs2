/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.internal

import cats.effect.kernel.{Fiber, Outcome}
import cats.effect.kernel.Resource.ExitCase
import cats.syntax.all._
import cats.{MonadError, ~>}
import fs2.{Chunk, CompositeFailure, Pull, Stream}
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[fs2] object PullCompile {
  import Pull._

  private sealed abstract case class View[+F[_], +O, X](step: Action[F, O, X])
      extends ViewL[F, O]
      with (Result[X] => Pull[F, O, Unit]) {
    def apply(r: Result[X]): Pull[F, O, Unit]
  }
  private final class EvalView[+F[_], +O](step: Action[F, O, Unit]) extends View[F, O, Unit](step) {
    def apply(r: Result[Unit]): Pull[F, O, Unit] = r
  }

  private final class BindView[+F[_], +O, Y](step: Action[F, O, Y], val b: Bind[F, O, Y, Unit])
      extends View[F, O, Y](step) {
    def apply(r: Result[Y]): Pull[F, O, Unit] = b.cont(r)
  }

  private class BindBind[F[_], O, X, Y](
      bb: Bind[F, O, X, Y],
      delegate: Bind[F, O, Y, Unit]
  ) extends Bind[F, O, X, Unit](bb.step) { self =>

    def cont(zr: Result[X]): Pull[F, O, Unit] =
      new Bind[F, O, Y, Unit](bb.cont(zr)) {
        override val delegate: Bind[F, O, Y, Unit] = self.delegate
        def cont(yr: Result[Y]): Pull[F, O, Unit] = delegate.cont(yr)
      }

  }

  /* unrolled view of Pull `bind` structure * */
  private def viewL[F[_], O](stream: Pull[F, O, Unit]): ViewL[F, O] = {

    @tailrec
    def mk(free: Pull[F, O, Unit]): ViewL[F, O] =
      free match {
        case r: Result[Unit]       => r
        case e: Action[F, O, Unit] => new EvalView[F, O](e)
        case b: Bind[F, O, y, Unit] =>
          b.step match {
            case e: Action[F, O, y2] => new BindView(e, b)
            case r: Result[_]        => mk(b.cont(r))
            case c: Bind[F, O, x, _] => mk(new BindBind[F, O, x, y](c, b.delegate))
          }
      }

    mk(stream)
  }

  private trait Run[F[_], G[_], X, End] {
    def done(scope: Scope[F]): End
    def out(head: Chunk[X], scope: Scope[F], tail: Pull[G, X, Unit]): End
    def interrupted(scopeId: Unique, err: Option[Throwable]): End
    def fail(e: Throwable): End
  }

  private type Cont[-Y, +G[_], +X] = Result[Y] => Pull[G, X, Unit]

  /* Left-folds the output of a stream.
   *
   * Interruption of the stream is tightly coupled between Pull and Scope.
   * Reason for this is unlike interruption of `F` type (e.g. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra.
   *
   * As such the `Unique` is passed to Result.Interrupted as glue between Pull that allows pass-along
   * the information to correctly compute recovery point after interruption was signalled via `Scope`.
   *
   * This token indicates scope of the computation where interruption actually happened.
   * This is used to precisely find most relevant interruption scope where interruption shall be resumed
   * for normal continuation of the stream evaluation.
   *
   * Interpreter uses this to find any parents of this scope that has to be interrupted, and guards the
   * interruption so it won't propagate to scope that shall not be anymore interrupted.
   */
  private def compileLoop[F[_], G[_], X, End](
      scope: Scope[F],
      extendsTopScope: Boolean,
      extendedTopScope: Option[Scope[F]],
      translation: G ~> F,
      endRunner: Run[F, G, X, F[End]],
      stream: Pull[G, X, Unit]
  )(implicit
      F: MonadError[F, Throwable]
  ): F[End] = {

    def go(
        stream: Pull[G, X, Unit],
        newScope: Scope[F] = scope,
        newExtendedScope: Option[Scope[F]] = extendedTopScope,
        newTranslation: G ~> F = translation,
        newRunner: Run[F, G, X, F[End]] = endRunner
    ): F[End] =
      compileLoop[F, G, X, End](
        newScope,
        extendsTopScope,
        newExtendedScope,
        newTranslation,
        newRunner,
        stream
      )

    def interruptGuard[Mid](
        scope: Scope[F],
        view: Cont[Nothing, G, X],
        runner: Run[F, G, X, F[Mid]]
    )(
        next: => F[Mid]
    ): F[Mid] =
      scope.isInterrupted.flatMap {
        case None => next
        case Some(outcome) =>
          val result = outcome match {
            case Outcome.Errored(err)       => Result.Fail(err)
            case Outcome.Canceled()         => Result.Interrupted(scope.id, None)
            case Outcome.Succeeded(scopeId) => Result.Interrupted(scopeId, None)
          }
          compileLoop[F, G, X, Mid](
            scope,
            extendsTopScope,
            extendedTopScope,
            translation,
            runner,
            view(result)
          )
      }

    def innerMapOutput[K[_], C, D](stream: Pull[K, C, Unit], fun: C => D): Pull[K, D, Unit] =
      viewL(stream) match {
        case r: Result[_] => r.asInstanceOf[Result[Unit]]
        case v: View[K, C, x] =>
          val mstep: Pull[K, D, x] = (v.step: Action[K, C, x]) match {
            case o: Output[C] =>
              try Output(o.values.map(fun))
              catch { case NonFatal(t) => Result.Fail(t) }
            case t: Translate[l, k, C] => // k= K
              Translate[l, k, D](innerMapOutput[l, C, D](t.stream, fun), t.fk)
            case s: Uncons[k, _]    => s
            case s: StepLeg[k, _]   => s
            case a: AlgEffect[k, _] => a
            case i: InScope[k, c] =>
              InScope[k, D](innerMapOutput(i.stream, fun), i.useInterruption)
            case m: MapOutput[k, b, c] => innerMapOutput(m.stream, fun.compose(m.fun))

            case fm: FlatMapOutput[k, b, c] =>
              // end result: a Pull[K, D, x]
              val innerCont: b => Pull[k, D, Unit] =
                (x: b) => innerMapOutput[k, c, D](fm.fun(x), fun)
              FlatMapOutput[k, b, D](fm.stream, innerCont)
          }
          new Bind[K, D, x, Unit](mstep) {
            def cont(r: Result[x]) = innerMapOutput(v(r), fun)
          }
      }

    def goErr(err: Throwable, view: Cont[Nothing, G, X]): F[End] =
      go(view(Result.Fail(err)))

    class ViewRunner(val view: Cont[Unit, G, X]) extends Run[F, G, X, F[End]] {
      private val prevRunner = endRunner

      def done(doneScope: Scope[F]): F[End] =
        go(view(Result.unit), newScope = doneScope)

      def out(head: Chunk[X], scope: Scope[F], tail: Pull[G, X, Unit]): F[End] = {
        @tailrec
        def outLoop(acc: Pull[G, X, Unit], pred: Run[F, G, X, F[End]]): F[End] =
          // bit of an ugly hack to avoid a stack overflow when these accummulate
          pred match {
            case vrun: ViewRunner =>
              val nacc = new Bind[G, X, Unit, Unit](acc) {
                def cont(r: Result[Unit]) = vrun.view(r)
              }
              outLoop(nacc, vrun.prevRunner)
            case _ => pred.out(head, scope, acc)
          }
        outLoop(tail, this)
      }

      def interrupted(tok: Unique, err: Option[Throwable]): F[End] =
        go(view(Result.Interrupted(tok, err)))

      def fail(e: Throwable): F[End] = goErr(e, view)
    }

    def goMapOutput[Z](mout: MapOutput[G, Z, X], view: Cont[Unit, G, X]): F[End] =
      go(innerMapOutput[G, Z, X](mout.stream, mout.fun), newRunner = new ViewRunner(view))

    abstract class StepRunR[Y, S](view: Cont[Option[S], G, X]) extends Run[F, G, Y, F[End]] {
      def done(scope: Scope[F]): F[End] =
        interruptGuard(scope, view, endRunner) {
          go(view(Result.Succeeded(None)), newScope = scope)
        }

      def interrupted(scopeId: Unique, err: Option[Throwable]): F[End] =
        go(view(Result.Interrupted(scopeId, err)))

      def fail(e: Throwable): F[End] = goErr(e, view)
    }

    class UnconsRunR[Y](view: Cont[Option[(Chunk[Y], Pull[G, Y, Unit])], G, X])
        extends StepRunR[Y, (Chunk[Y], Pull[G, Y, Unit])](view) {

      def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
        // For a Uncons, we continue in same Scope at which we ended compilation of inner stream
        interruptGuard(outScope, view, endRunner) {
          go(view(Result.Succeeded(Some((head, tail)))), newScope = outScope)
        }
    }

    class StepLegRunR[Y](view: Cont[Option[Stream.StepLeg[G, Y]], G, X])
        extends StepRunR[Y, Stream.StepLeg[G, Y]](view) {

      def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
        // StepLeg: we shift back to the scope at which we were
        // before we started to interpret the Leg's inner stream.
        interruptGuard(scope, view, endRunner) {
          val result = Result.Succeeded(Some(new Stream.StepLeg(head, outScope.id, tail)))
          go(view(result), newScope = scope)
        }
    }

    def goFlatMapOut[Y](fmout: FlatMapOutput[G, Y, X], view: View[G, X, Unit]): F[End] = {
      val runner = new FlatMapR(view, fmout.fun)
      // The F.unit is needed because otherwise an stack overflow occurs.
      F.unit >> compileLoop(
        scope,
        extendsTopScope,
        extendedTopScope,
        translation,
        runner,
        fmout.stream
      )
    }

    class FlatMapR[Y](outView: View[G, X, Unit], fun: Y => Pull[G, X, Unit])
        extends Run[F, G, Y, F[End]] {
      private[this] def unconsed(chunk: Chunk[Y], tail: Pull[G, Y, Unit]): Pull[G, X, Unit] =
        if (chunk.size == 1 && tail.isInstanceOf[Result.Succeeded[_]])
          // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
          // check if hd has only a single element, and if so, process it directly instead of folding.
          // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
          try fun(chunk(0))
          catch { case NonFatal(e) => Result.Fail(e) }
        else {
          def go(idx: Int): Pull[G, X, Unit] =
            if (idx == chunk.size)
              FlatMapOutput[G, Y, X](tail, fun)
            else {
              try fun(chunk(idx)).transformWith {
                case Result.Succeeded(_) => go(idx + 1)
                case Result.Fail(err)    => Result.Fail(err)
                case interruption @ Result.Interrupted(_, _) =>
                  FlatMapOutput[G, Y, X](interruptBoundary(tail, interruption), fun)
              } catch { case NonFatal(e) => Result.Fail(e) }
            }

          go(0)
        }

      def done(scope: Scope[F]): F[End] =
        interruptGuard(scope, outView, endRunner) {
          go(outView(Result.unit), newScope = scope)
        }

      def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
        interruptGuard(outScope, outView, endRunner) {
          val fmoc = unconsed(head, tail)
          val next = outView match {
            case ev: EvalView[G, X] => fmoc
            case bv: BindView[G, X, Unit] =>
              val del = bv.b.asInstanceOf[Bind[G, X, Unit, Unit]].delegate
              new Bind[G, X, Unit, Unit](fmoc) {
                override val delegate: Bind[G, X, Unit, Unit] = del
                def cont(yr: Result[Unit]): Pull[G, X, Unit] = delegate.cont(yr)
              }
          }

          go(next, newScope = outScope)
        }

      def interrupted(scopeId: Unique, err: Option[Throwable]): F[End] =
        go(outView(Result.Interrupted(scopeId, err)))

      def fail(e: Throwable): F[End] = goErr(e, outView)
    }

    def goEval[V](eval: Eval[G, V], view: Cont[V, G, X]): F[End] =
      scope.interruptibleEval(translation(eval.value)).flatMap { eitherOutcome =>
        val result = eitherOutcome match {
          case Right(r)                       => Result.Succeeded(r)
          case Left(Outcome.Errored(err))     => Result.Fail(err)
          case Left(Outcome.Canceled())       => Result.Interrupted(scope.id, None)
          case Left(Outcome.Succeeded(token)) => Result.Interrupted(token, None)
        }
        go(view(result))
      }

    def goAcquire[R](acquire: Acquire[G, R], view: Cont[R, G, X]): F[End] = {
      val onScope = scope.acquireResource[R](
        poll =>
          if (acquire.cancelable) poll(translation(acquire.resource))
          else translation(acquire.resource),
        (resource, exit) => translation(acquire.release(resource, exit))
      )

      val cont = onScope.flatMap { outcome =>
        val result = outcome match {
          case Outcome.Succeeded(Right(r))      => Result.Succeeded(r)
          case Outcome.Succeeded(Left(scopeId)) => Result.Interrupted(scopeId, None)
          case Outcome.Canceled()               => Result.Interrupted(scope.id, None)
          case Outcome.Errored(err)             => Result.Fail(err)
        }
        go(view(result))
      }
      interruptGuard(scope, view, endRunner)(cont)
    }

    def goInterruptWhen(
        haltOnSignal: F[Either[Throwable, Unit]],
        view: Cont[Unit, G, X]
    ): F[End] = {
      val onScope = scope.acquireResource(
        _ => scope.interruptWhen(haltOnSignal),
        (f: Fiber[F, Throwable, Unit], _: ExitCase) => f.cancel
      )
      val cont = onScope.flatMap { outcome =>
        val result = outcome match {
          case Outcome.Succeeded(Right(_))      => Result.Succeeded(())
          case Outcome.Succeeded(Left(scopeId)) => Result.Interrupted(scopeId, None)
          case Outcome.Canceled()               => Result.Interrupted(scope.id, None)
          case Outcome.Errored(err)             => Result.Fail(err)
        }
        go(view(result))
      }
      interruptGuard(scope, view, endRunner)(cont)
    }

    def boundToScope(scopeId: Unique, result: Result[Unit]): Pull[G, X, Unit] = result match {
      case Result.Succeeded(_) =>
        CloseScope(scopeId, None, ExitCase.Succeeded)
      case interrupted @ Result.Interrupted(_, _) =>
        CloseScope(scopeId, Some(interrupted), ExitCase.Canceled)
      case Result.Fail(err) =>
        CloseScope(scopeId, None, ExitCase.Errored(err)).transformWith {
          case Result.Succeeded(_) => Result.Fail(err)
          case Result.Fail(err0)   => Result.Fail(CompositeFailure(err, err0, Nil))
          case Result.Interrupted(interruptedScopeId, _) =>
            sys.error(
              s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
            )
        }
    }

    def goInScope(
        stream: Pull[G, X, Unit],
        useInterruption: Boolean,
        view: Cont[Unit, G, X]
    ): F[End] = {
      val maybeCloseExtendedScope: F[Option[Scope[F]]] =
        // If we're opening a new top-level scope (aka, direct descendant of root),
        // close the current extended top-level scope if it is defined.
        if (scope.isRoot && extendedTopScope.isDefined)
          extendedTopScope.traverse_(_.close(ExitCase.Succeeded).rethrow).as(None)
        else
          F.pure(extendedTopScope)

      val vrun = new ViewRunner(view)
      val tail = maybeCloseExtendedScope.flatMap { newExtendedScope =>
        scope.open(useInterruption).rethrow.flatMap { childScope =>
          val bb = new Bind[G, X, Unit, Unit](stream) {
            def cont(r: Result[Unit]) = boundToScope(childScope.id, r)
          }
          go(bb, newScope = childScope, newExtendedScope = newExtendedScope, newRunner = vrun)
        }
      }
      interruptGuard(scope, view, vrun)(tail)
    }

    def goCloseScope(close: CloseScope, view: Cont[Unit, G, X]): F[End] = {
      def closeResult(r: Either[Throwable, Unit], ancestor: Scope[F]): Result[Unit] =
        close.interruption match {
          case None => Result.fromEither(r)
          case Some(Result.Interrupted(interruptedScopeId, err)) =>
            def err1 = CompositeFailure.fromList(r.swap.toOption.toList ++ err.toList)
            if (ancestor.descendsFrom(interruptedScopeId))
              // we still have scopes to interrupt, lets build interrupted tail
              Result.Interrupted(interruptedScopeId, err1)
            else
              // interrupts scope was already interrupted, resume operation
              err1 match {
                case None      => Result.unit
                case Some(err) => Result.Fail(err)
              }
        }

      scope.findInLineage(close.scopeId).flatMap {
        case None => go(view(close.interruption.getOrElse(Result.unit)))
          // scope already closed, continue with current scope

        case Some(toClose) if toClose.isRoot =>
          // Impossible - don't close root scope as a result of a `CloseScope` call
          go(view(Result.unit))

        case Some(toClose) if extendsTopScope && toClose.level == 1 =>
          // Request to close the current top-level scope - if we're supposed to extend
          // it instead, leave the scope open and pass it to the continuation
          extendedTopScope.traverse_(_.close(ExitCase.Succeeded).rethrow) *>
            toClose.openAncestor.flatMap { ancestor =>
              go(view(Result.unit), newScope = ancestor, newExtendedScope = Some(toClose))
            }

        case Some(toClose) =>
          toClose.close(close.exitCase).flatMap { r =>
            toClose.openAncestor.flatMap { ancestor =>
              val res = closeResult(r, ancestor)
              go(view(res), newScope = ancestor)
            }
          }

      }
    }

    viewL(stream) match {
      case _: Result.Succeeded[_]  => endRunner.done(scope)
      case failed: Result.Fail     => endRunner.fail(failed.error)
      case int: Result.Interrupted => endRunner.interrupted(int.context, int.deferredError)

      case view: View[G, X, y] =>
        view.step match {
          case output: Output[_] => // y = Unit
            interruptGuard(scope, view, endRunner)(
              endRunner.out(output.values, scope, view(Result.unit))
            )

          case fmout: FlatMapOutput[g, z, X] => // y = Unit
            goFlatMapOut[z](fmout, view.asInstanceOf[View[g, X, Unit]])

          case tst: Translate[h, g, X] => // y = Unit
            val composed: h ~> F = translation.asInstanceOf[g ~> F].compose[h](tst.fk)

            val translateRunner: Run[F, h, X, F[End]] = new Run[F, h, X, F[End]] {
              def done(scope: Scope[F]): F[End] = endRunner.done(scope)
              def out(head: Chunk[X], scope: Scope[F], tail: Pull[h, X, Unit]): F[End] =
                endRunner.out(head, scope, Translate(tail, tst.fk))
              def interrupted(scopeId: Unique, err: Option[Throwable]): F[End] =
                endRunner.interrupted(scopeId, err)
              def fail(e: Throwable): F[End] =
                endRunner.fail(e)
            }
            compileLoop[F, h, X, End](
              scope,
              extendsTopScope,
              extendedTopScope,
              composed,
              translateRunner,
              tst.stream
            )

          case u: Uncons[G, z] =>
            val v = view.asInstanceOf[View[G, X, Option[(Chunk[z], Pull[G, z, Unit])]]]
            // a Uncons is run on the same scope, without shifting.
            compileLoop(
              scope,
              extendsTopScope,
              extendedTopScope,
              translation,
              new UnconsRunR(v),
              u.stream
            )

          case u: StepLeg[G, z] =>
            val v = view.asInstanceOf[View[G, X, Option[Stream.StepLeg[G, z]]]]
            scope.shiftScope(u.scope, u.toString).flatMap { nscope =>
              val runner = new StepLegRunR(v)
              compileLoop(nscope, extendsTopScope, extendedTopScope, translation, runner, u.stream)
            }

          case _: GetScope[_]           => go(view(Result.Succeeded(scope.asInstanceOf[y])))
          case mout: MapOutput[g, z, X] => goMapOutput[z](mout, view)
          case eval: Eval[G, r]         => goEval[r](eval, view)
          case acquire: Acquire[G, _]   => goAcquire(acquire, view)
          case inScope: InScope[g, X]   => goInScope(inScope.stream, inScope.useInterruption, view)
          case int: InterruptWhen[g]    => goInterruptWhen(translation(int.haltOnSignal), view)
          case close: CloseScope        => goCloseScope(close, view)
        }
    }
  }

  private[fs2] def compile[F[_], O, B](
      stream: Pull[F, O, Unit],
      initScope: Scope[F],
      extendsTopScope: Boolean,
      init: B
  )(foldChunk: (B, Chunk[O]) => B)(implicit
      F: MonadError[F, Throwable]
  ): F[B] = {
    val initFk: F ~> F = cats.arrow.FunctionK.id[F]

    class OuterRun(accB: B) extends Run[F, F, O, F[B]] { self =>
      override def done(scope: Scope[F]): F[B] = F.pure(accB)

      override def fail(e: Throwable): F[B] = F.raiseError(e)

      override def interrupted(scopeId: Unique, err: Option[Throwable]): F[B] =
        err.fold(F.pure(accB))(F.raiseError)

      override def out(head: Chunk[O], scope: Scope[F], tail: Pull[F, O, Unit]): F[B] =
        try {
          val nrunner = new OuterRun(foldChunk(accB, head))
          compileLoop[F, F, O, B](scope, extendsTopScope, None, initFk, nrunner, tail)
        } catch {
          case NonFatal(e) =>
            viewL(tail) match {
              case Result.Succeeded(_) => F.raiseError(e)
              case Result.Fail(e2)     => F.raiseError(CompositeFailure(e2, e))
              case Result.Interrupted(_, err) =>
                F.raiseError(err.fold(e)(t => CompositeFailure(e, t)))
              case v: View[F, O, Unit] =>
                val next = v(Result.Fail(e))
                compileLoop[F, F, O, B](
                  scope,
                  extendsTopScope,
                  None,
                  initFk,
                  self,
                  next
                )
            }
        }
    }

    compileLoop[F, F, O, B](initScope, extendsTopScope, None, initFk, new OuterRun(init), stream)
  }

  /* Inject interruption to the tail used in flatMap.  Assures that close of the scope
   * is invoked if at the flatMap tail, otherwise switches evaluation to `interrupted` path*/
  private[this] def interruptBoundary[F[_], O](
      stream: Pull[F, O, Unit],
      interruption: Result.Interrupted
  ): Pull[F, O, Unit] =
    viewL(stream) match {
      case interrupted: Result.Interrupted => interrupted // impossible
      case _: Result.Succeeded[_]          => interruption
      case failed: Result.Fail =>
        val mixed = CompositeFailure
          .fromList(interruption.deferredError.toList :+ failed.error)
          .getOrElse(failed.error)
        Result.Fail(mixed)

      case view: View[F, O, _] =>
        view.step match {
          case CloseScope(scopeId, _, _) =>
            // Inner scope is getting closed b/c a parent was interrupted
            CloseScope(scopeId, Some(interruption), ExitCase.Canceled).transformWith(view)
          case _ =>
            // all other cases insert interruption cause
            view(interruption)
        }
    }

}
