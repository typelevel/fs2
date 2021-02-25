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

package fs2

import cats.effect._
import cats.effect.kernel.Resource
import cats.syntax.all._

sealed trait Shared[F[_], A] {
  def resource: Resource[F, A]
}

object Shared {
  def allocate[F[_], A](
      resource: Resource[F, A]
  )(implicit F: Concurrent[F]): Resource[F, (Shared[F, A], A)] = {
    final case class State(value: A, finalizer: F[Unit], permits: Int) {
      def addPermit: State = copy(permits = permits + 1)
      def releasePermit: State = copy(permits = permits - 1)
    }

    MonadCancel[Resource[F, *]].uncancelable { _ =>
      for {
        shared <- Resource.eval(resource.allocated.flatMap { case (a, fin) =>
          F.ref[Option[State]](Some(State(a, fin, 0))).map { state =>
            def acquire: F[A] =
              state.modify {
                case Some(st) => (Some(st.addPermit), F.pure(st.value))
                case None =>
                  (None, F.raiseError[A](new Throwable("finalization has already occurred")))
              }.flatten

            def release: F[Unit] =
              state.modify {
                case Some(st) if st.permits > 1 => (Some(st.releasePermit), F.unit)
                case Some(st)                   => (None, st.finalizer)
                case None                       => (None, F.raiseError[Unit](new Throwable("can't finalize")))
              }.flatten

            new Shared[F, A] {
              override def resource: Resource[F, A] =
                Resource.make(acquire)(_ => release)
            }
          }
        })
        a <- shared.resource
      } yield (shared, a)
    }
  }
}
