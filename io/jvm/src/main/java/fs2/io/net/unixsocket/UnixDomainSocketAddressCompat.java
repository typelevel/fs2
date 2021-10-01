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
import java.lang.invoke.MethodType;
import java.net.SocketAddress;

final class UnixDomainSocketAddressCompat {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final Class<?> UNIX_DOMAIN_SOCKET_ADDRESS_CLASS = initUnixDomainSocketAddressClass();

  private static final MethodType OF_METHOD_TYPE = MethodType.methodType(UNIX_DOMAIN_SOCKET_ADDRESS_CLASS,
      String.class);
  private static final MethodHandle OF_METHOD_HANDLE = initOfMethodHandle();

  private static Class<?> initUnixDomainSocketAddressClass() {
    try {
      return Class.forName("java.net.UnixDomainSocketAddress");
    } catch (ClassNotFoundException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static MethodHandle initOfMethodHandle() {
    try {
      return LOOKUP.findStatic(UNIX_DOMAIN_SOCKET_ADDRESS_CLASS, "of", OF_METHOD_TYPE);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  static SocketAddress of(String pathname) throws Throwable {
    return (SocketAddress) OF_METHOD_HANDLE.invoke(pathname);
  }
}
