/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect

import cats._
import cats.data.AndThen
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.effect.implicits._

import scala.annotation.tailrec

import Resource.ExitCase

/**
  * TODO
  * initial scaladoc
  * port tests (ResourceSyntax, ResourceTests, ResourceJVMTests)
  *  look at IOSpec, it's mostly going to be that (plus a mixin for the jvm stuff)
  *  otoh I need to port the generators
  *
  *  also need to write compilation & runtime test once SyncIO is here
  *
  * Strategy for blocking for the Java closeable interfaces
  * add useForever
  * change bracket instances to follow same code org strategy as the others
  * add implicit not found to bracket
  * check that the comment on ExitCase.Completed is still valid
  */

/**
  * The `Resource` is a data structure that captures the effectful
  * allocation of a resource, along with its finalizer.
  *
  * This can be used to wrap expensive resources. Example:
  *
  * {{{
  *   def open(file: File): Resource[IO, BufferedReader] =
  *     Resource(IO {
  *       val in = new BufferedReader(new FileReader(file))
  *       (in, IO(in.close()))
  *     })
  * }}}
  *
  * Usage is done via [[Resource!.use use]] and note that resource usage nests,
  * because its implementation is specified in terms of [[Bracket]]:
  *
  * {{{
  *   open(file1).use { in1 =>
  *     open(file2).use { in2 =>
  *       readFiles(in1, in2)
  *     }
  *   }
  * }}}
  *
  * `Resource` forms a `MonadError` on the resource type when the
  * effect type has a `cats.MonadError` instance. Nested resources are
  * released in reverse order of acquisition. Outer resources are
  * released even if an inner use or release fails.
  *
  * {{{
  *   def mkResource(s: String) = {
  *     val acquire = IO(println(s"Acquiring $$s")) *> IO.pure(s)
  *     def release(s: String) = IO(println(s"Releasing $$s"))
  *     Resource.make(acquire)(release)
  *   }
  *
  *   val r = for {
  *     outer <- mkResource("outer")
  *     inner <- mkResource("inner")
  *   } yield (outer, inner)
  *
  *   r.use { case (a, b) =>
  *     IO(println(s"Using $$a and $$b"))
  *   }
  * }}}
  *
  * On evaluation the above prints:
  * {{{
  *   Acquiring outer
  *   Acquiring inner
  *   Using outer and inner
  *   Releasing inner
  *   Releasing outer
  * }}}
  *
  * A `Resource` is nothing more than a data structure, an ADT, described by
  * the following node types and that can be interpreted if needed:
  *
  *  - [[cats.effect.Resource.Allocate Allocate]]
  *  - [[cats.effect.Resource.Suspend Suspend]]
  *  - [[cats.effect.Resource.Bind Bind]]
  *
  * Normally users don't need to care about these node types, unless conversions
  * from `Resource` into something else is needed (e.g. conversion from `Resource`
  * into a streaming data type).
  *
  * @tparam F the effect type in which the resource is allocated and released
  * @tparam A the type of resource
  */
sealed abstract class Resource[+F[_], +A] {
  import Resource.{Allocate, Bind, Suspend}

  private def fold[G[x] >: F[x], B](
      onOutput: A => G[B],
      onRelease: G[Unit] => G[Unit]
  )(implicit G: Resource.Bracket[G]): G[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[G, Any], stack: List[Any => Resource[G, Any]]): G[Any] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(current: Resource[G, Any], stack: List[Any => Resource[G, Any]]): G[Any] =
      current match {
        case a: Allocate[G, Any] =>
          G.bracketCase(a.resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput.asInstanceOf[Any => G[Any]](a)
                case l   => continue(l.head(a), l.tail)
              }
          } {
            case ((_, release), ec) =>
              onRelease(release(ec))
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack))
      }
    loop(this.asInstanceOf[Resource[G, Any]], Nil).asInstanceOf[G[B]]
  }

  /**
    * Allocates a resource and supplies it to the given function.
    * The resource is released as soon as the resulting `F[B]` is
    * completed, whether normally or as a raised error.
    *
    * @param f the function to apply to the allocated resource
    * @return the result of applying [F] to
    */
  def use[G[x] >: F[x], B](f: A => G[B])(implicit G: Resource.Bracket[G[*]]): G[B] =
    fold[G, B](f, identity)

  /**
    * Allocates two resources concurrently, and combines their results in a tuple.
    *
    * The finalizers for the two resources are also run concurrently with each other,
    * but within _each_ of the two resources, nested finalizers are run in the usual
    * reverse order of acquisition.
    *
    * Note that `Resource` also comes with a `cats.Parallel` instance
    * that offers more convenient access to the same functionality as
    * `parZip`, for example via `parMapN`:
    *
    * {{{
    *   def mkResource(name: String) = {
    *     val acquire =
    *       IO(scala.util.Random.nextInt(1000).millis).flatMap(IO.sleep) *>
    *       IO(println(s"Acquiring $$name")).as(name)
    *
    *     val release = IO(println(s"Releasing $$name"))
    *     Resource.make(acquire)(release)
    *   }
    *
    *  val r = (mkResource("one"), mkResource("two"))
    *             .parMapN((s1, s2) => s"I have \$s1 and \$s2")
    *             .use(msg => IO(println(msg)))
    * }}}
    */
  def parZip[G[x] >: F[x]: Async, B](
      that: Resource[G[*], B]
  ): Resource[G[*], (A, B)] = {
    type Update = (G[Unit] => G[Unit]) => G[Unit]

    def allocate[C](r: Resource[G, C], storeFinalizer: Update): G[C] =
      r.fold[G, C](
        _.pure[G],
        release => storeFinalizer(Resource.Bracket[G].guarantee(_)(release))
      )

    val bothFinalizers = Ref[G].of(Sync[G].unit -> Sync[G].unit)

    Resource
      .make(bothFinalizers)(_.get.flatMap(_.parTupled).void)
      .evalMap { store =>
        val leftStore: Update = f => store.update(_.leftMap(f))
        val rightStore: Update = f => store.update(_.map(f))

        (allocate(this, leftStore), allocate(that, rightStore)).parTupled
      }
  }

  /**
    * Implementation for the `flatMap` operation, as described via the
    * `cats.Monad` type class.
    */
  def flatMap[G[x] >: F[x], B](f: A => Resource[G[*], B]): Resource[G[*], B] =
    Bind(this, f)

  /**
    *  Given a mapping function, transforms the resource provided by
    *  this Resource.
    *
    *  This is the standard `Functor.map`.
    */
  def map[G[x] >: F[x], B](f: A => B)(implicit F: Applicative[G[*]]): Resource[G[*], B] =
    flatMap(a => Resource.pure[G, B](f(a)))

  /**
    * Given a natural transformation from `F` to `G`, transforms this
    * Resource from effect `F` to effect `G`.
    */
  def mapK[G[x] >: F[x], H[_]](
      f: G ~> H
  )(implicit D: Defer[H], G: Applicative[H]): Resource[H, A] =
    this match {
      case Allocate(resource) =>
        Allocate(f(resource).map { case (a, r) => (a, r.andThen(u => f(u))) })
      case Bind(source, f0) =>
        Bind(Suspend(D.defer(G.pure(source.mapK(f)))), f0.andThen(_.mapK(f)))
      case Suspend(resource) =>
        Suspend(f(resource).map(_.mapK(f)))
    }

  /**
    * Given a `Resource`, possibly built by composing multiple
    * `Resource`s monadically, returns the acquired resource, as well
    * as an action that runs all the finalizers for releasing it.
    *
    * If the outer `F` fails or is interrupted, `allocated` guarantees
    * that the finalizers will be called. However, if the outer `F`
    * succeeds, it's up to the user to ensure the returned `F[Unit]`
    * is called once `A` needs to be released. If the returned
    * `F[Unit]` is not called, the finalizers will not be run.
    *
    * For this reason, this is an advanced and potentially unsafe api
    * which can cause a resource leak if not used correctly, please
    * prefer [[use]] as the standard way of running a `Resource`
    * program.
    *
    * Use cases include interacting with side-effectful apis that
    * expect separate acquire and release actions (like the `before`
    * and `after` methods of many test frameworks), or complex library
    * code that needs to modify or move the finalizer for an existing
    * resource.
    */
  def allocated[G[x] >: F[x], B >: A](implicit G: Resource.Bracket[G[*]]): G[(B, G[Unit])] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]],
        release: G[Unit]
    ): G[(Any, G[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]],
        release: G[Unit]
    ): G[(Any, G[Unit])] =
      current match {
        case a: Allocate[G, Any] =>
          G.bracketCase(a.resource) {
            case (a, rel) =>
              stack match {
                case Nil => G.pure(a -> G.guarantee(rel(ExitCase.Completed))(release))
                case l   => continue(l.head(a), l.tail, G.guarantee(rel(ExitCase.Completed))(release))
              }
          } {
            case (_, ExitCase.Completed) =>
              G.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack, release)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack, release))
      }

    loop(this.asInstanceOf[Resource[F, Any]], Nil, G.unit).map {
      case (a, release) =>
        (a.asInstanceOf[A], release)
    }
  }

  /**
    * Applies an effectful transformation to the allocated resource. Like a
    * `flatMap` on `F[A]` while maintaining the resource context
    */
  def evalMap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G[*]]): Resource[G[*], B] =
    this.flatMap(a => Resource.liftF(f(a)))

  /**
    * Applies an effectful transformation to the allocated resource. Like a
    * `flatTap` on `F[A]` while maintaining the resource context
    */
  def evalTap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G[*]]): Resource[G[*], A] =
    this.evalMap(a => f(a).as(a))
}

object Resource extends ResourceInstances {

  /**
    * Creates a resource from an allocating effect.
    *
    * @see [[make]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effect to release it
    */
  def apply[F[_], A](resource: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, A] =
    Allocate[F, A] {
      resource.map {
        case (a, release) =>
          (a, (_: ExitCase) => release)
      }
    }

  /**
    * Creates a resource from an allocating effect, with a finalizer
    * that is able to distinguish between [[ExitCase exit cases]].
    *
    * @see [[makeCase]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effectful function to release it
    */
  def applyCase[F[_], A](resource: F[(A, ExitCase => F[Unit])]): Resource[F, A] =
    Allocate(resource)

  /**
    * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
    */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.Suspend(fr)

  /**
    * Creates a resource from an acquiring effect and a release function.
    *
    * This builder mirrors the signature of [[Bracket.bracket]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    apply[F, A](acquire.map(a => a -> release(a)))

  /**
    * Creates a resource from an acquiring effect and a release function that can
    * discriminate between different [[ExitCase exit cases]].
    *
    * This builder mirrors the signature of [[Bracket.bracketCase]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def makeCase[F[_], A](
      acquire: F[A]
  )(release: (A, ExitCase) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, e => release(a, e))))

  /**
    * Lifts a pure value into a resource. The resource has a no-op release.
    *
    * @param a the value to lift into a resource
    */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate((a, (_: ExitCase) => F.unit).pure[F])

  /**
    * Lifts an applicative into a resource. The resource has a no-op release.
    * Preserves interruptibility of `fa`.
    *
    * @param fa the value to lift into a resource
    */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.suspend(fa.map(a => Resource.pure[F, A](a)))

  /**
    * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
    */
  def liftK[F[_]](implicit F: Applicative[F]): F ~> Resource[F, *] =
    new (F ~> Resource[F, *]) {
      def apply[A](fa: F[A]): Resource[F, A] = Resource.liftF(fa)
    }

  /**
    * Creates a [[Resource]] by wrapping a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
    *
    * Example:
    * {{{
    *   import cats.effect._
    *   import scala.io.Source
    *
    *   def reader[F[_]](data: String)(implicit F: Sync[F]): Resource[F, Source] =
    *     Resource.fromAutoCloseable(F.delay {
    *       Source.fromString(data)
    *     })
    * }}}
    * @param acquire The effect with the resource to acquire.
    * @param F the effect type in which the resource was acquired and will be released
    * @tparam F the type of the effect
    * @tparam A the type of the autocloseable resource
    * @return a Resource that will automatically close after use
    */
  def fromAutoCloseable[F[_], A <: AutoCloseable](
      acquire: F[A]
  )(implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.delay(autoCloseable.close()))

  // /**
  //  * Creates a [[Resource]] by wrapping a Java
  //  * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]]
  //  * which is blocking in its adquire and close operations.
  //  *
  //  * Example:
  //  * {{{
  //  *   import java.io._
  //  *   import cats.effect._
  //  *
  //  *   def reader[F[_]](file: File, blocker: Blocker)(implicit F: Sync[F], cs: ContextShift[F]): Resource[F, BufferedReader] =
  //  *     Resource.fromAutoCloseableBlocking(blocker)(F.delay {
  //  *       new BufferedReader(new FileReader(file))
  //  *     })
  //  * }}}
  //  * @param acquire The effect with the resource to acquire
  //  * @param blocker The blocking context that will be used to compute acquire and close
  //  * @tparam F the type of the effect
  //  * @tparam A the type of the autocloseable resource
  //  * @return a Resource that will automatically close after use
  //  */
  // def fromAutoCloseableBlocking[F[_]: Sync: ContextShift, A <: AutoCloseable](
  //   blocker: Blocker
  // )(acquire: F[A]): Resource[F, A] =
  //   Resource.make(blocker.blockOn(acquire))(autoCloseable => blocker.delay(autoCloseable.close()))

  /**
    * `Resource` data constructor that wraps an effect allocating a resource,
    * along with its finalizers.
    */
  final case class Allocate[F[_], A](resource: F[(A, ExitCase => F[Unit])]) extends Resource[F, A]

  /**
    * `Resource` data constructor that encodes the `flatMap` operation.
    */
  final case class Bind[F[_], S, +A](source: Resource[F, S], fs: S => Resource[F, A])
      extends Resource[F, A]

  /**
    * `Resource` data constructor that suspends the evaluation of another
    * resource value.
    */
  final case class Suspend[F[_], A](resource: F[Resource[F, A]]) extends Resource[F, A]

  /**
    * Type for signaling the exit condition of an effectful
    * computation, that may either succeed, fail with an error or
    * get canceled.
    *
    * The types of exit signals are:
    *
    *  - [[ExitCase$.Completed Completed]]: for successful completion
    *  - [[ExitCase$.Error Error]]: for termination in failure
    *  - [[ExitCase$.Canceled Canceled]]: for abortion
    */
  sealed trait ExitCase extends Product with Serializable
  object ExitCase {

    /**
      * An [[ExitCase]] that signals successful completion.
      *
      * Note that "successful" is from the type of view of the
      * `MonadError` type that's implementing [[Bracket]].
      * When combining such a type with `EitherT` or `OptionT` for
      * example, this exit condition might not signal a successful
      * outcome for the user, but it does for the purposes of the
      * `bracket` operation.
      */
    case object Completed extends ExitCase

    /**
      * An [[ExitCase]] signaling completion in failure.
      */
    final case class Errored(e: Throwable) extends ExitCase

    /**
      * An [[ExitCase]] signaling that the action was aborted.
      *
      * As an example this can happen when we have a cancelable data type,
      * like [[IO]] and the task yielded by `bracket` gets canceled
      * when it's at its `use` phase.
      *
      * Thus [[Bracket]] allows you to observe interruption conditions
      * and act on them.
      */
    case object Canceled extends ExitCase
  }

  trait Bracket[F[_]] extends MonadError[F, Throwable] {
    def bracketCase[A, B](acquire: F[A])(use: A => F[B])(release: (A, ExitCase) => F[Unit]): F[B]

    def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
      bracketCase(acquire)(use)((a, _) => release(a))

    def guarantee[A](fa: F[A])(finalizer: F[Unit]): F[A] =
      bracket(unit)(_ => fa)(_ => finalizer)

    def guaranteeCase[A](fa: F[A])(finalizer: ExitCase => F[Unit]): F[A] =
      bracketCase(unit)(_ => fa)((_, e) => finalizer(e))
  }

  trait Bracket0 {
    implicit def catsEffectResourceBracketForSync[F[_]](implicit
        F: Sync[F]
    ): Bracket[F] =
      new Bracket[F] {
        def bracketCase[A, B](
            acquire: F[A]
        )(use: A => F[B])(release: (A, ExitCase) => F[Unit]): F[B] =
          flatMap(acquire) { a =>
            val handled = onError(use(a)) {
              case e => void(attempt(release(a, ExitCase.Errored(e))))
            }
            flatMap(handled)(b => as(attempt(release(a, ExitCase.Completed)), b))
          }

        def pure[A](x: A): F[A] = F.pure(x)
        def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
        def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
        def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
        def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
      }
  }

  object Bracket extends Bracket0 {
    def apply[F[_]](implicit F: Bracket[F]): F.type = F

    implicit def catsEffectResourceBracketForConcurrent[F[_]](implicit
        F: Concurrent[F, Throwable]
    ): Bracket[F] =
      new Bracket[F] {
        def bracketCase[A, B](
            acquire: F[A]
        )(use: A => F[B])(release: (A, ExitCase) => F[Unit]): F[B] =
          F.uncancelable { poll =>
            flatMap(acquire) { a =>
              val finalized = F.onCancel(poll(use(a)), release(a, ExitCase.Canceled))
              val handled = onError(finalized) {
                case e => void(attempt(release(a, ExitCase.Errored(e))))
              }
              flatMap(handled)(b => as(attempt(release(a, ExitCase.Completed)), b))
            }
          }
        def pure[A](x: A): F[A] = F.pure(x)
        def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
        def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
        def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
        def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
      }
  }

  /**
    * Newtype encoding for a `Resource` datatype that has a `cats.Applicative`
    * capable of doing parallel processing in `ap` and `map2`, needed
    * for implementing `cats.Parallel`.
    *
    * Helpers are provided for converting back and forth in `Par.apply`
    * for wrapping any `IO` value and `Par.unwrap` for unwrapping.
    *
    * The encoding is based on the "newtypes" project by
    * Alexander Konovalov, chosen because it's devoid of boxing issues and
    * a good choice until opaque types will land in Scala.
    * [[https://github.com/alexknvl/newtypes alexknvl/newtypes]].
    */
  type Par[+F[_], +A] = Par.Type[F, A]

  object Par {
    type Base
    trait Tag extends Any
    type Type[+F[_], +A] <: Base with Tag

    def apply[F[_], A](fa: Resource[F, A]): Type[F, A] =
      fa.asInstanceOf[Type[F, A]]

    def unwrap[F[_], A](fa: Type[F, A]): Resource[F, A] =
      fa.asInstanceOf[Resource[F, A]]
  }
}

abstract private[effect] class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectMonadErrorForResource[F[_], E](implicit
      F0: MonadError[F, E]
  ): MonadError[Resource[F, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A](implicit
      F0: Monad[F],
      A0: Monoid[A]
  ): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
      def F = F0
    }

  // implicit def catsEffectLiftIOForResource[F[_]](implicit F00: LiftIO[F], F10: Applicative[F]): LiftIO[Resource[F, *]] =
  //   new ResourceLiftIO[F] {
  //     def F0 = F00
  //     def F1 = F10
  //   }

  implicit def catsEffectCommutativeApplicativeForResourcePar[F[_]](implicit
      F: Async[F]
  ): CommutativeApplicative[Resource.Par[F, *]] =
    new ResourceParCommutativeApplicative[F] {
      def F0 = F
    }

  implicit def catsEffectParallelForResource[F0[_]: Async]
      : Parallel.Aux[Resource[F0, *], Resource.Par[F0, *]] =
    new ResourceParallel[F0] {
      def F0 = catsEffectCommutativeApplicativeForResourcePar
      def F1 = catsEffectMonadForResource
    }
}

abstract private[effect] class ResourceInstances0 {
  implicit def catsEffectMonadForResource[F[_]](implicit F0: Monad[F]): Monad[Resource[F, *]] =
    new ResourceMonad[F] {
      def F = F0
    }

  implicit def catsEffectSemigroupForResource[F[_], A](implicit
      F0: Monad[F],
      A0: Semigroup[A]
  ): ResourceSemigroup[F, A] =
    new ResourceSemigroup[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](implicit
      F0: Monad[F],
      K0: SemigroupK[F]
  ): ResourceSemigroupK[F] =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
    }
}

abstract private[effect] class ResourceMonadError[F[_], E]
    extends ResourceMonad[F]
    with MonadError[Resource[F, *], E] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa match {
      case Allocate(fa) =>
        Allocate[F, Either[E, A]](F.attempt(fa).map {
          case Left(error)         => (Left(error), (_: ExitCase) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, Any], fs: (Any => Resource[F, A])) =>
        Suspend(F.pure(source).map[Resource[F, Either[E, A]]] { source =>
          Bind(
            attempt(source),
            (r: Either[E, Any]) =>
              r match {
                case Left(error) => Resource.pure[F, Either[E, A]](Left(error))
                case Right(s)    => attempt(fs(s))
              }
          )
        })
      case Suspend(resource) =>
        Suspend(resource.attempt.map {
          case Left(error)               => Resource.pure[F, Either[E, A]](Left(error))
          case Right(fa: Resource[F, A]) => attempt(fa)
        })
    }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure[F, A](a)
      case Left(e)  => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource.applyCase[F, A](F.raiseError(e))
}

abstract private[effect] class ResourceMonad[F[_]] extends Monad[Resource[F, *]] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: Monad[F]

  override def map[A, B](fa: Resource[F, A])(f: A => B): Resource[F, B] =
    fa.map(f)

  def pure[A](a: A): Resource[F, A] =
    Resource.applyCase[F, A](F.pure((a, _ => F.unit)))

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Resource[F, Either[A, B]]): Resource[F, B] = {
    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r match {
        case a: Allocate[F, Either[A, B]] =>
          Suspend(a.resource.flatMap[Resource[F, B]] {
            case (Left(a), release) =>
              release(ExitCase.Completed).map(_ => tailRecM(a)(f))
            case (Right(b), release) =>
              F.pure(Allocate[F, B](F.pure((b, release))))
          })
        case s: Suspend[F, Either[A, B]] =>
          Suspend(s.resource.map(continue))
        case b: Bind[F, _, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }
}

abstract private[effect] class ResourceMonoid[F[_], A]
    extends ResourceSemigroup[F, A]
    with Monoid[Resource[F, A]] {
  implicit protected def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure[F, A](A.empty)
}

abstract private[effect] class ResourceSemigroup[F[_], A] extends Semigroup[Resource[F, A]] {
  implicit protected def F: Monad[F]
  implicit protected def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

abstract private[effect] class ResourceSemigroupK[F[_]] extends SemigroupK[Resource[F, *]] {
  implicit protected def F: Monad[F]
  implicit protected def K: SemigroupK[F]

  def combineK[A](rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
      xy <- Resource.liftF(K.combineK(x.pure[F], y.pure[F]))
    } yield xy
}

// abstract private[effect] class ResourceLiftIO[F[_]] extends LiftIO[Resource[F, *]] {
//   implicit protected def F0: LiftIO[F]
//   implicit protected def F1: Applicative[F]

//   def liftIO[A](ioa: IO[A]): Resource[F, A] =
//     Resource.liftF(F0.liftIO(ioa))
// }

abstract private[effect] class ResourceParCommutativeApplicative[F[_]]
    extends CommutativeApplicative[Resource.Par[F, *]] {
  import Resource.Par
  import Resource.Par.{unwrap, apply => par}

  implicit protected def F0: Async[F]

  final override def map[A, B](fa: Par[F, A])(f: A => B): Par[F, B] =
    par(unwrap(fa).map(f))
  final override def pure[A](x: A): Par[F, A] =
    par(Resource.pure[F, A](x))
  final override def product[A, B](fa: Par[F, A], fb: Par[F, B]): Par[F, (A, B)] =
    par(unwrap(fa).parZip(unwrap(fb)))
  final override def map2[A, B, Z](fa: Par[F, A], fb: Par[F, B])(f: (A, B) => Z): Par[F, Z] =
    map(product(fa, fb)) { case (a, b) => f(a, b) }
  final override def ap[A, B](ff: Par[F, A => B])(fa: Par[F, A]): Par[F, B] =
    map(product(ff, fa)) { case (ff, a) => ff(a) }
}

abstract private[effect] class ResourceParallel[F0[_]] extends Parallel[Resource[F0, *]] {
  protected def F0: Applicative[Resource.Par[F0, *]]
  protected def F1: Monad[Resource[F0, *]]

  type F[x] = Resource.Par[F0, x]

  final override val applicative: Applicative[Resource.Par[F0, *]] = F0
  final override val monad: Monad[Resource[F0, *]] = F1

  final override val sequential: Resource.Par[F0, *] ~> Resource[F0, *] =
    new (Resource.Par[F0, *] ~> Resource[F0, *]) {
      def apply[A](fa: Resource.Par[F0, A]): Resource[F0, A] = Resource.Par.unwrap(fa)
    }

  final override val parallel: Resource[F0, *] ~> Resource.Par[F0, *] =
    new (Resource[F0, *] ~> Resource.Par[F0, *]) {
      def apply[A](fa: Resource[F0, A]): Resource.Par[F0, A] = Resource.Par(fa)
    }
}
