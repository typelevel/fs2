package fs2

object concurrent {

  def join[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    for {
      killSignal <- Stream.eval(async.signalOf(false))
      openLeases <- Stream.eval(async.mutable.Semaphore(maxOpen))
      outerDone <- Stream.eval(F.refOf[Boolean](false))
      outputQueue <- Stream.eval(async.synchronousQueue[F,Option[Either[Throwable,Chunk[O]]]])
      o <- s.evalMap { (s: Stream[F,O]) =>
        F.bind(openLeases.decrement) { _ =>
        F.start {
          val done =
            F.bind(openLeases.increment) { _ =>
            F.bind(F.get(outerDone)) { outerDone =>
            F.bind(openLeases.count) { n =>
              if (n == maxOpen && outerDone) outputQueue.enqueue1(None)
              else F.pure(())
            }}}
          s.interruptWhen(killSignal).chunks.attempt.evalMap(o => outputQueue.enqueue1(Some(o)))
           .onFinalize(done).run.run
        }}
      }.onFinalize(F.setPure(outerDone)(true)) mergeDrainL {
        outputQueue.dequeue.through(pipe.unNoneTerminate).through(pipe.rethrow).flatMap(Stream.chunk)
      } onFinalize (killSignal.set(true))
    } yield o
  }
}
