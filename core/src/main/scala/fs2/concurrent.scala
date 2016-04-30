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
    for {
      killSignal <- Stream.eval(async.signalOf(false))
      openLeases <- Stream.eval(async.mutable.Semaphore(maxOpen))
      outerDone <- Stream.eval(F.refOf[Boolean](false))
      outputQueue <- Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Chunk[O]]])
      o <- outer.evalMap { (inner: Stream[F,O]) =>
        F.bind(openLeases.decrement) { _ =>
        F.start {
          val done =
            F.bind(openLeases.increment) { _ =>
            F.bind(F.get(outerDone)) { outerDone =>
            F.bind(openLeases.count) { n =>
              if (n == maxOpen && outerDone) {
                outputQueue.enqueue1(None)
              }
              else F.pure(())
            }}}
          inner.chunks.attempt.evalMap { o => outputQueue.enqueue1(Some(o)) }
          .interruptWhen(killSignal)
          .onFinalize(done)
          .run.run
        }}
      }.onFinalize(F.bind(openLeases.count) { n =>
          F.bind(if (n == maxOpen) outputQueue.offer1(None) else F.pure(true)) { _ =>
            F.setPure(outerDone)(true)
          }
      }) mergeDrainL {
        outputQueue.dequeue.through(pipe.unNoneTerminate).flatMap {
          case Left(e) => Stream.eval(killSignal.set(true)).flatMap { _ => Stream.fail(e) }
          case Right(c) => Stream.chunk(c)
        }
      } onFinalize {
        // set kill signal, then wait for any outstanding inner streams to complete
        F.bind(killSignal.set(true)) { _ =>
        F.bind(openLeases.clear) { m => openLeases.decrementBy(maxOpen - m) }}
      }
    } yield o
  }
}
