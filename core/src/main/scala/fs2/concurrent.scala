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

    case class State(outerDone: Boolean, open: Int, killed: Boolean)

    def throttle[A](stateSignal: async.mutable.Signal[F,State]): Pipe[F,Stream[F,A],Stream[F,A]] = {
      def go(open: Int): (Stream.Handle[F,State], Stream.Handle[F,Stream[F,A]]) => Pull[F,Stream[F,A],Unit] = (state, s) => {
          if (open < maxOpen) {
            s.receive1 { case inner #: s =>
              val monitoredStream = {
                Stream.bracket(stateSignal.possiblyModify { s => Some(s.copy(open = s.open + 1)) })(
                  _ => inner,
                  _ => F.map(stateSignal.possiblyModify { s => Some(s.copy(open = s.open - 1)) }) { _ => () }
                )
              }
              Pull.output1(monitoredStream) >> go(open + 1)(state, s)
            }
          } else {
            state.receive1 { case now #: state => go(now.open)(state, s) }
          }
        }
      s => stateSignal.discrete.pull2(s)(go(0))
    }

    for {
      stateSignal <- Stream.eval(async.signalOf(State(false, 0, false)))
      outputQueue <- Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Chunk[O]]])
      o <- outer.map { inner =>
        inner.chunks.attempt.evalMap { o =>
          outputQueue.enqueue1(Some(o))
        }.interruptWhen(stateSignal.map { _.killed })
      }.through(throttle(stateSignal)).evalMap { inner =>
        F.start(inner.run.run)
      }.onFinalize {
        F.map(stateSignal.possiblyModify { s => Some(s.copy(outerDone = true)) }) { _ => () }
      }.mergeDrainL {
        stateSignal.discrete.takeWhile { s => !s.outerDone || (s.outerDone && s.open != 0) }.onFinalize {
          outputQueue.enqueue1(None)
        }
      }.mergeDrainL {
        outputQueue.dequeue.through(pipe.unNoneTerminate).flatMap {
          case Left(e) => Stream.eval(stateSignal.possiblyModify(s => Some(s.copy(killed = true)))).flatMap { _ => Stream.fail(e) }
          case Right(c) => Stream.chunk(c)
        }
      }.onFinalize {
        F.bind(stateSignal.possiblyModify(s => if (s.killed) None else Some(s.copy(killed = true)))) { _ =>
          stateSignal.discrete.takeWhile { s => s.open > 0 }.run.run
        }
      }
    } yield o
  }
}
