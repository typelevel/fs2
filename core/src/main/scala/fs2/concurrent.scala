package fs2

import util.Free

object concurrent {

  def join[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    for {
      killSignal <- Stream.eval(async.signalOf(false))
      openLeases <- Stream.eval(async.mutable.Semaphore(maxOpen))
      outerDone <- Stream.eval(F.refOf[Boolean](false))
      outputQueue <- Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Chunk[O]]])
      o <- s.evalMap { (s: Stream[F,O]) =>
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
          s.chunks.attempt.evalMap { o => outputQueue.enqueue1(Some(o)) }
           .interruptWhen(killSignal).run.flatMap { _ => Free.eval(done) }.run
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
