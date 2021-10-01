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

package fs2.io.net.unixsocket;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.StandardProtocolFamily;

final class StandardProtocolFamilyCompat {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle UNIX_METHOD_HANDLE = initUnixMethodHandle();

  private static MethodHandle initUnixMethodHandle() {
    try {
      return LOOKUP.findStaticGetter(StandardProtocolFamily.class, "UNIX", StandardProtocolFamily.class);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  static final StandardProtocolFamily UNIX = initUnix();

  private static StandardProtocolFamily initUnix() {
    try {
      return (StandardProtocolFamily) UNIX_METHOD_HANDLE.invokeExact();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
