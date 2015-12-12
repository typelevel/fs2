package fs2.async.mutable


import fs2.Stream
import fs2.async.AsyncExt.Change

import fs2.async.{AsyncExt, immutable}
import fs2.util.{Catchable,Monad}
import fs2.util.Task.Callback

import scala.collection.immutable.{Queue => SQueue}
import scala.util.{Success, Try}

/**
 * Created by pach on 10/10/15.
 */
/**
 * A signal whose value may be set asynchronously. Provides continuous
 * and discrete streams for responding to changes to it's value.
 *
 */
trait Signal[F[_],A] extends immutable.Signal[F,A] {


  /**
   * Asynchronously refreshes the value of the signal,
   * keep the value of this `Signal` the same, but notify any listeners.
   *
   */
  def refresh:F[Unit]

  /**
   * Sets the value of this `Signal`.
   *
   */
  def set(a: A): F[Unit]


  /**
   * Asynchronously sets the current value of this `Signal` and returns new value of this `Signal`.
   *
   * `op` is consulted to set this signal. It is supplied with current value to either
   * set the value (returning Some) or no-op (returning None)
   *
   * `F` returns the result of applying `op` to current value.
   *
   */
   def compareAndSet(op: A => Option[A]) : F[Option[A]]

}


object Signal {


  private type State[F[_],A] = (Int,A,SQueue[((Int,A)) => F[Unit]])

  def constant[F[_],A](a: A)(implicit F: Monad[F]): immutable.Signal[F,A] = new immutable.Signal[F,A] {
    def get = F.pure(a)
    def continuous = Stream.constant(a)
    def discrete = Stream.empty // never changes, so never any updates
    def changes = Stream.empty
  }

  def apply[F[_],A](initA:A)(implicit F:AsyncExt[F]): F[Signal[F,A]] =
    F.bind(F.ref[State[F,A]]) { ref =>
    F.map(F.set(ref)(F.pure((0,initA,SQueue.empty)))) { _ =>
      def getChanged(stamp:Int):F[(Int,A)] = {
        F.bind(F.ref[(Int,A)]){ chref =>
          val modify =
            F.modify(ref){ case s@(a,current,q) =>
              if (current != stamp) F.pure(s)
              else F.pure((a,current,q :+ {(va:((Int,A))) => F.set(chref)(F.pure(va))}))
            }

          F.bind(modify){
            case Change((current,a,_), _) =>
              if (current != stamp) F.pure((current,a))
              else F.get(chref)
          }
        }
      }

      def getStamped:F[(Int,A)] = F.map(F.get(ref)){case (v,a,_) => (v,a)}

      new Signal[F,A] {
        def refresh: F[Unit] = F.map(compareAndSet(a => Some(a)))(_ => ())
        def set(a: A): F[Unit] = F.map(compareAndSet(_ => Some(a)))(_ => ())
        def get: F[A] = F.map(F.get(ref))(_._2)
        def compareAndSet(op: (A) => Option[A]): F[Option[A]] = {
          val modify:F[Change[State[F,A]]] =
            F.modify(ref) { case (v,a,q) => F.pure(op(a).fold((v,a,q)){ na => (v+1,na,SQueue.empty)}) }

          F.bind(modify) {
           case Change((oldVersion,_,queued),(newVersion,newA,_)) =>
             if (oldVersion == newVersion) F.pure(None:Option[A])
             else {
               queued.foldLeft(F.pure(Option(newA))) {
                 case (r,f) =>  F.bind(f(newVersion -> newA))(_ => r)
               }
             }
          }
        }

        def changes: fs2.Stream[F, Unit] =
          discrete.map(_ => ())

        def continuous: fs2.Stream[F, A] =
          Stream.eval(get).append(continuous)

        def discrete: fs2.Stream[F, A] = {
          def go(stamp:Int):fs2.Stream[F, A] = {
            Stream.eval(getChanged(stamp))
            .flatMap { case (s,a) => Stream(a).append(go(s)) }
          }
          Stream.eval(getStamped).flatMap { case (s,a) => Stream(a).append(go(s))}
        }

      }
    }}
}
