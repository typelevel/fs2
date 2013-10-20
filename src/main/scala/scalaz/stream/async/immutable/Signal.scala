package scalaz.stream.async.immutable

import scalaz.stream.async.generic
import scalaz.stream.{process1, Wye, Tee, Process1}
import scalaz._
import scala.collection.immutable.Vector


trait Signal[A] extends generic.Signal[A] {
  self: Signal[A] =>

  /**
   * Maps the signal over to new signal using f
   */
  def map[B](f: A => B): Signal[B] = new Signal[B] {

    def discrete = self.discrete.map(f)

    def continuous = self.continuous.map(f)

  }

  /**
   * For each signal of this Signal creates new signal by applying (f). 
   * When the new signal terminates, it is appended with next invocation of (f). 
   */
  def flatMap[B](f: A => Signal[B]) = new Signal[B] {
    def discrete = self.discrete.flatMap(a => f(a).discrete)

    def continuous = self.continuous.flatMap(a => f(a).continuous)
  }


  /**
   * Appends `s` to current signal when this signal terminates
   */
  def append[B >: A](s: => Signal[B]): Signal[B] = new Signal[B] {
    def discrete = self.discrete ++ s.discrete

    def continuous = self.continuous ++ s.continuous
  }

  /**
   * Switch to fallback when the signal completes or to cleanup when signal terminates with error 
   */
  def orElse[B >: A](fallback: => Signal[B], cleanup: => Signal[B]) = new Signal[B] {
    def discrete = self.discrete orElse(fallback.discrete, cleanup.discrete)

    def continuous = self.continuous orElse(fallback.continuous, cleanup.continuous)
  }

  /**
   * Switch to p2 when signal terminates with error
   */
  def onFailure[B >: A](p2: => Signal[B]) = new Signal[B] {
    def discrete = self.discrete onFailure p2.discrete

    def continuous = self.continuous onFailure p2.continuous
  }

  /**
   * Switch to p2 when signal terminates or fails with error
   */
  def onComplete[B >: A](p2: => Signal[B]) = new Signal[B] {
    def discrete = self.discrete onComplete p2.discrete

    def continuous = self.continuous onComplete p2.continuous
  }

  /**
   * Pipes output of this signal through Process1
   */
  def pipe[B](p: Process1[A, B]): Signal[B] = new Signal[B] {
    def discrete = self.discrete.pipe(p)

    def continuous = self.continuous.pipe(p)
  }

  /** alias for `pipe` */
  def |>[B](p: Process1[A, B]): Signal[B] = pipe(p)


  /**
   * Creates signal by applying supplied `tee` to both signals 
   */
  def tee[B, C](s2: Signal[B])(t: Tee[A, B, C]): Signal[C] = new Signal[C] {
    def discrete = self.discrete.tee(s2.discrete)(t)

    def continuous = self.continuous.tee(s2.continuous)(t)
  }

  /**
   * Creates signal by applying supplied `wye` to both signals 
   */
  def wye[B, C](s2: Signal[B])(y: Wye[A, B, C]): Signal[C] = new Signal[C] {
    def discrete = self.discrete.wye(s2.discrete)(y)

    def continuous = self.continuous.wye(s2.continuous)(y)
  }


  //////////////////////////////////////////////////////////////////////////
  // Process aliases


  /** Alias for `this |> process1.buffer(n)`. */
  def buffer(n: Int): Signal[A] =
    this |> process1.buffer(n)

  /** Alias for `this |> process1.bufferBy(f)`. */
  def bufferBy(f: A => Boolean): Signal[A] =
    this |> process1.bufferBy(f)

  /** Alias for `this |> process1.chunk(n)`. */
  def chunk(n: Int): Signal[Vector[A]] =
    this |> process1.chunk(n)

  /** Alias for `this |> process1.chunkBy(f)`. */
  def chunkBy(f: A => Boolean): Signal[Vector[A]] =
    this |> process1.chunkBy(f)

  /** Alias for `this |> process1.chunkBy2(f)`. */
  def chunkBy2(f: (A, A) => Boolean): Signal[Vector[A]] =
    this |> process1.chunkBy2(f)

  /** Alias for `this |> process1.collect(pf)`. */
  def collect[B](pf: PartialFunction[A, B]): Signal[B] =
    this |> process1.collect(pf)

  /** Alias for `this |> process1.split(f)` */
  def split(f: A => Boolean): Signal[Vector[A]] =
    this |> process1.split(f)

  /** Alias for `this |> process1.splitOn(f)` */
  def splitOn[P >: A](p: P)(implicit P: Equal[P]): Signal[Vector[P]] =
    this |> process1.splitOn(p)

  /** Alias for `this |> process1.window(n)` */
  def window(n: Int): Signal[Vector[A]] =
    this |> process1.window(n)

  /** Alias for `this |> process1.drop(n)` */
  def drop(n: Int): Signal[A] =
    this |> process1.drop[A](n)

  /** Alias for `this |> process1.dropWhile(f)` */
  def dropWhile(f: A => Boolean): Signal[A] =
    this |> process1.dropWhile(f)

  /** Alias for `this |> process1.filter(f)` */
  def filter(f: A => Boolean): Signal[A] =
    this |> process1.filter(f)

  /** Alias for `this |> process1.find(f)` */
  def find(f: A => Boolean): Signal[A] =
    this |> process1.find(f)

  /** Connect this `Process` to `process1.fold(b)(f)`. */
  def fold[B >: A](b: B)(f: (B, B) => B): Signal[B] =
    this |> process1.fold(b)(f)

  /** Alias for `this |> process1.foldMonoid(M)` */
  def foldMonoid[B >: A](implicit M: Monoid[B]): Signal[B] =
    this |> process1.foldMonoid(M)

  /** Alias for `this |> process1.foldMap(f)(M)`. */
  def foldMap[B](f: A => B)(implicit M: Monoid[B]): Signal[B] =
    this |> process1.foldMap(f)(M)

  /** Connect this `Process` to `process1.fold1(f)`. */
  def fold1[B >: A](f: (B, B) => B): Signal[B] =
    this |> process1.fold1(f)

  /** Alias for `this |> process1.fold1Monoid(M)` */
  def fold1Monoid[B >: A](implicit M: Monoid[B]): Signal[B] =
    this |> process1.fold1Monoid(M)

  /** Alias for `this |> process1.fold1Semigroup(M)`. */
  def foldSemigroup[B >: A](implicit M: Semigroup[B]): Signal[B] =
    this |> process1.foldSemigroup(M)

  /** Alias for `this |> process1.fold1Map(f)(M)`. */
  def fold1Map[B](f: A => B)(implicit M: Monoid[B]): Signal[B] =
    this |> process1.fold1Map(f)(M)

  /** Alias for `this |> process1.reduce(f)`. */
  def reduce[B >: A](f: (B, B) => B): Signal[B] =
    this |> process1.reduce(f)

  /** Alias for `this |> process1.reduceMonoid(M)`. */
  def reduceMonoid[B >: A](implicit M: Monoid[B]): Signal[B] =
    this |> process1.reduceMonoid(M)

  /** Alias for `this |> process1.reduceSemigroup(M)`. */
  def reduceSemigroup[B >: A](implicit M: Semigroup[B]): Signal[B] =
    this |> process1.reduceSemigroup(M)

  /** Alias for `this |> process1.reduceMap(f)(M)`. */
  def reduceMap[B](f: A => B)(implicit M: Monoid[B]): Signal[B] =
    this |> process1.reduceMap(f)(M)

  /** Insert `sep` between elements emitted by this `Signal`. */
  def intersperse[B >: A](sep: B): Signal[B] =
    this |> process1.intersperse(sep)

  /** Alternate emitting elements from `this` and `p2`, starting with `this`. */
  def interleave[B >: A](s2: Signal[B]): Signal[B] =
    this.tee(s2)(scalaz.stream.tee.interleave)

  /** Halts this `Process` after emitting 1 element. */
  def once: Signal[A] = take(1)

  /** Skips all elements emitted by this `Signal` except the last. */
  def last: Signal[A] = this |> process1.last

  /** Connect this `Process` to `process1.scan(b)(f)`. */
  def scan[B](b: B)(f: (B, A) => B): Signal[B] =
    this |> process1.scan(b)(f)

  /** Connect this `Process` to `process1.scanMonoid(M)`. */
  def scanMonoid[B >: A](implicit M: Monoid[B]): Signal[B] =
    this |> process1.scanMonoid(M)

  /** Alias for `this |> process1.scanMap(f)(M)`. */
  def scanMap[B](f: A => B)(implicit M: Monoid[B]): Signal[B] =
    this |> process1.scanMap(f)(M)

  /** Connect this `Process` to `process1.scan1(f)`. */
  def scan1[B >: A](f: (B, B) => B): Signal[B] =
    this |> process1.scan1(f)

  /** Connect this `Process` to `process1.scan1Monoid(M)`. */
  def scan1Monoid[B >: A](implicit M: Monoid[B]): Signal[B] =
    this |> process1.scan1Monoid(M)

  /** Connect this `Process` to `process1.scan1Semigroup(M)`. */
  def scanSemigroup[B >: A](implicit M: Semigroup[B]): Signal[B] =
    this |> process1.scanSemigroup(M)

  /** Alias for `this |> process1.scan1Map(f)(M)`. */
  def scan1Map[B](f: A => B)(implicit M: Monoid[B]): Signal[B] =
    this |> process1.scan1Map(f)(M)

  /** Halts this `Signal` after emitting `n` elements. */
  def take(n: Int): Signal[A] =
    this |> process1.take[A](n)

  /** Halts this `Signal` as soon as the predicate tests false. */
  def takeWhile(f: A => Boolean): Signal[A] =
    this |> process1.takeWhile(f)


  /** Call `tee` with the `zipWith` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zipWith[B, C](s2: Signal[B])(f: (A, B) => C): Signal[C] =
    this.tee(s2)(scalaz.stream.tee.zipWith(f))

  /** Call `tee` with the `zip` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zip[B](s2: Signal[B]): Signal[(A, B)] =
    this.tee(s2)(scalaz.stream.tee.zip)

  /** Nondeterministic version of `zipWith`. */
  def yipWith[B, C](s2: Signal[B])(f: (A, B) => C): Signal[C] =
    this.wye(s2)(scalaz.stream.wye.yipWith(f))

  /** Nondeterministic version of `zip`. */
  def yip[B](s2: Signal[B]): Signal[(A, B)] =
    this.wye(s2)(scalaz.stream.wye.yip)

  /** Nondeterministic interleave of both signals. Emits values whenever either is defined. */
  def merge[B >: A](s2: Signal[B]): Signal[B] =
    this.wye(s2)(scalaz.stream.wye.merge)

  /** Nondeterministic interleave of both signals. Emits values whenever either is defined. */
  def either[B](s2: Signal[B]): Signal[A \/ B] =
    this.wye(s2)(scalaz.stream.wye.either)


}
