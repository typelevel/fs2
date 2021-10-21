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

package fs2.io.internal

import java.util.concurrent.locks.AbstractQueuedSynchronizer

/** An alternative implementation of [[java.util.concurrent.Semaphore]] which holds ''at most'' 1
  * permit. In the case that this synchronizer is not acquired, any calls to
  * [[Synchronizer#release]] will ''not'' increase the permit count, i.e. this synchronizer can
  * still only be acquired by a ''single'' thread calling [[Synchronizer#acquire]].
  */
private final class Synchronizer {
  private[this] val underlying = new AbstractQueuedSynchronizer {
    // There is 1 available permit when the synchronizer is constructed.
    setState(1)

    override def tryAcquire(arg: Int): Boolean = compareAndSetState(1, 0)

    override def tryRelease(arg: Int): Boolean = {
      // Unconditionally make 1 permit available for taking. This operation
      // limits the available permits to at most 1 at any time.
      setState(1)
      true
    }
  }

  @throws[InterruptedException]
  def acquire(): Unit = underlying.acquireInterruptibly(0)

  def release(): Unit = {
    underlying.release(0)
    ()
  }
}
