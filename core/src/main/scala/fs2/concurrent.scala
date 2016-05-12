package fs2


object concurrent {

  /**
    * Joins non deterministically streams.
    *
    *
    * @param maxOpen    Allows to specify maximum open streams at any given time.
    *                   The outer stream will stop its evaluation when this limit is reached and will
    *                   start evaluating once currently open streams will be <= mayOpen.
    *                   MaxOpen must be > 0
    *
    * @param outer      Outer stream, that produces streams (inner) to be run concurrently.
    *                   When this stops gracefully, then all inner streams are continuing to run
    *                   resulting in process that will stop when all `inner` streams finished
    *                   their evaluation.
    *
    *                   When this stream fails, then evaluation of all `inner` streams is interrupted
    *                   and resulting stream will fail with same failure.
    *
    *                   When any of `inner` streams fails, then this stream is interrupted and
    *                   all other `inner` streams are interrupted as well, resulting in stream that fails
    *                   with error of the stream that cased initial failure.
    */
  // todo: document when the finalizers are calle in which situation
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    assert(maxOpen > 0,"maxOpen must be > 0, was: " + maxOpen)

    def throttle[A](checkIfKilled: F[Boolean], allDone: F[Unit]): Pipe[F,Stream[F,A],Unit] = {

      def runInnerStream(inner: Stream[F,A], onInnerStreamDone: F[Unit]): Pull[F,Nothing,Unit] = {
        val startInnerStream: F[F.Ref[Unit]] = {
          F.bind(F.ref[Unit]) { gate =>
          F.map(F.start(Stream.eval(checkIfKilled).
                           flatMap { killed => if (killed) Stream.empty else inner }.
                           onFinalize { F.bind(F.setPure(gate)(())) { _ => onInnerStreamDone } }.
                           run.run
          )) { _ => gate }}
        }
        Pull.acquire(startInnerStream)(gate => F.get(gate)).map { gate => () }
      }

      def awaitAllDone(open: Int, q: async.mutable.Queue[F,Unit]): F[Unit] = {
        def loop(open: Int): Stream.Handle[F,Unit] => Pull[F,Nothing,Unit] = h => {
          println(" - Awaiting all done: " + open)
          if (open == 0) Pull.eval(allDone)
          else h.receive1 { case _ #: h => loop(open - 1)(h) }
        }
        F.map(F.start(q.dequeue.pull(loop(open)).run.run)) { _ => () }
      }

      def go(doneQueue: async.mutable.Queue[F,Unit])(open: Int): (Stream.Handle[F,Stream[F,A]], Stream.Handle[F,Unit]) => Pull[F,Nothing,Unit] = (h, d) => {
        if (open < maxOpen)
          Pull.receive1Option[F,Stream[F,A],Nothing,Unit] {
            case Some(inner #: h) => runInnerStream(inner, doneQueue.enqueue1(())).flatMap { gate => go(doneQueue)(open + 1)(h, d) }
            case None =>
              Pull.eval(awaitAllDone(open, doneQueue))
          }(h)
        else
          Pull.eval(checkIfKilled).flatMap { killed =>
            if (killed) Pull.eval(awaitAllDone(open, doneQueue))
            else d.receive1 { case _ #: d =>
              println(" - Inner stream completed: " + (open - 1))
              go(doneQueue)(open - 1)(h, d)
            }
          }
      }

      in => Stream.eval(async.unboundedQueue[F,Unit]).flatMap { doneQueue => in.pull2(doneQueue.dequeue)(go(doneQueue)(0)) }
    }

    for {
      killSignal <- Stream.eval(async.signalOf(false))
      _ = println("--------------------------------------------------------------------------------")
      outputQueue <- Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Chunk[O]]])
      o <- outer.map { inner =>
        inner.chunks.attempt.evalMap { o => outputQueue.enqueue1(Some(o)) }.interruptWhen(killSignal)
      }.through(throttle(killSignal.get, outputQueue.enqueue1(None))).mergeDrainL {
        outputQueue.dequeue.through(pipe.unNoneTerminate).flatMap {
          case Left(e) => Stream.eval(killSignal.possiblyModify(_ => Some(true))).flatMap { _ => println("set kill signal due to error"); Stream.fail(e) }
          case Right(c) => Stream.chunk(c)
        }
      }.onFinalize {
        F.map(killSignal.possiblyModify(_ => Some(true))) { _ => println("set kill signal") }
      }
    } yield o
  }
}
