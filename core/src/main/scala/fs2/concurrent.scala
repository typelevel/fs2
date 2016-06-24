package fs2


object concurrent {

  /**
    * Nondeterministically merges a stream of streams (`outer`) in to a single stream,
    * opening at most `maxOpen` streams at any point in time.
    *
    * The outer stream is evaluated and each resulting inner stream is run concurrently,
    * up to `maxOpen` stream. Once this limit is reached, evaluation of the outer stream
    * is paused until one or more inner streams finish evaluating.
    *
    * When the outer stream stops gracefully, all inner streams continue to run,
    * resulting in a stream that will stop when all inner streams finish
    * their evaluation.
    *
    * When the outer stream fails, evaluation of all inner streams is interrupted
    * and the resulting stream will fail with same failure.
    *
    * When any of the inner streams fail, then the outer stream and all other inner
    * streams are interrupted, resulting in stream that fails with the error of the
    * stream that cased initial failure.
    *
    * Finalizers on each inner stream are run at the end of the inner stream,
    * concurrently with other stream computations.
    *
    * Finalizers on the outer stream are run after all inner streams have been pulled
    * from the outer stream -- hence, finalizers on the outer stream will likely run
    * before the last finalizer on the last inner stream.
    *
    * Finalizers on the returned stream are run after the outer stream has finished
    * and all open inner streams have finished.
    *
    * @param maxOpen    Maximum number of open inner streams at any time. Must be > 0.
    * @param outer      Stream of streams to join.
    */
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    assert(maxOpen > 0,"maxOpen must be > 0, was: " + maxOpen)

    def throttle[A](checkIfKilled: F[Boolean]): Pipe[F,Stream[F,A],Unit] = {

      def runInnerStream(inner: Stream[F,A], doneQueue: async.mutable.Queue[F,Pull[F,Nothing,Unit]]): Pull[F,Nothing,Unit] = {
        Pull.eval(F.ref[Pull[F,Nothing,Unit]]).flatMap { earlyReleaseRef =>
          val startInnerStream: F[F.Ref[Unit]] = {
            F.bind(F.ref[Unit]) { gate =>
            F.map(F.start(
              Stream.eval(checkIfKilled).
                     flatMap { killed => if (killed) Stream.empty else inner }.
                     onFinalize {
                       F.bind(F.setPure(gate)(())) { _ =>
                         F.bind(F.get(earlyReleaseRef)) { earlyRelease =>
                           F.map(doneQueue.enqueue1(earlyRelease))(_ => ())
                         }
                       }
                     }.
                     run
            )) { _ => gate }}
          }
          Pull.acquireCancellable(startInnerStream) { gate => F.get(gate) }.flatMap { case (release, _) => Pull.eval(F.setPure(earlyReleaseRef)(release)) }
        }
      }

      def go(doneQueue: async.mutable.Queue[F,Pull[F,Nothing,Unit]])(open: Int): (Stream.Handle[F,Stream[F,A]], Stream.Handle[F,Pull[F,Nothing,Unit]]) => Pull[F,Nothing,Unit] = (h, d) => {
        if (open < maxOpen)
          Pull.receive1Option[F,Stream[F,A],Nothing,Unit] {
            case Some(inner #: h) => runInnerStream(inner, doneQueue).flatMap { gate => go(doneQueue)(open + 1)(h, d) }
            case None => Pull.done
          }(h)
        else
          d.receive1 { case earlyRelease #: d => earlyRelease >> go(doneQueue)(open - 1)(h, d) }
      }

      in => Stream.eval(async.unboundedQueue[F,Pull[F,Nothing,Unit]]).flatMap { doneQueue => in.pull2(doneQueue.dequeue)(go(doneQueue)(0)) }
    }

    for {
      killSignal <- Stream.eval(async.signalOf(false))
      outputQueue <- Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Chunk[O]]])
      o <- outer.map { inner =>
        inner.chunks.attempt.evalMap { o => outputQueue.enqueue1(Some(o)) }.interruptWhen(killSignal)
      }.through(throttle(killSignal.get)).onFinalize {
        outputQueue.enqueue1(None)
      }.mergeDrainL {
        outputQueue.dequeue.through(pipe.unNoneTerminate).flatMap {
          case Left(e) => Stream.eval(killSignal.set(true)).flatMap { _ => Stream.fail(e) }
          case Right(c) => Stream.chunk(c)
        }
      }.onFinalize { killSignal.set(true) }
    } yield o
  }
}
