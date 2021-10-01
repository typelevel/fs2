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

package fs2.io.net.tls;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.net.ssl.SSLParameters;

final class SSLParametersCompat extends SSLParameters {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodType SET_ENABLE_RETRANSMISSIONS_METHOD_TYPE = MethodType.methodType(void.class,
      boolean.class);
  private static final MethodHandle SET_ENABLE_RETRANSMISSIONS_METHOD_HANDLE = initSetEnableRetransmissionsMethodHandle();
  private static final MethodType SET_MAXIMUM_PACKET_SIZE_METHOD_TYPE = MethodType.methodType(void.class, int.class);
  private static final MethodHandle SET_MAXIMUM_PACKET_SIZE_METHOD_HANDLE = initSetMaximumPacketSizeMethodHandle();

  private static MethodHandle initSetEnableRetransmissionsMethodHandle() {
    try {
      return LOOKUP.findVirtual(SSLParametersCompat.class, "setEnableRetransmissions",
          SET_ENABLE_RETRANSMISSIONS_METHOD_TYPE);
    } catch (NoSuchMethodException e) {
      try {
        return LOOKUP.findVirtual(SSLParametersCompat.class, "fauxSetEnableRetransmissions",
            SET_ENABLE_RETRANSMISSIONS_METHOD_TYPE);
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private static MethodHandle initSetMaximumPacketSizeMethodHandle() {
    try {
      return LOOKUP.findVirtual(SSLParametersCompat.class, "setMaximumPacketSize", SET_MAXIMUM_PACKET_SIZE_METHOD_TYPE);
    } catch (NoSuchMethodException e) {
      try {
        return LOOKUP.findVirtual(SSLParametersCompat.class, "fauxSetMaximumPacketSize",
            SET_MAXIMUM_PACKET_SIZE_METHOD_TYPE);
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  void setEnableRetransmissionsCompat(boolean enableRetransmissions) throws Throwable {
    SET_ENABLE_RETRANSMISSIONS_METHOD_HANDLE.invokeExact(enableRetransmissions);
  }

  void setMaximumPacketSizeCompat(int maximumPacketSize) throws Throwable {
    SET_MAXIMUM_PACKET_SIZE_METHOD_HANDLE.invokeExact(maximumPacketSize);
  }

  private void fauxSetEnableRetransmissions(boolean setEnableRetransmissions) {
  }

  private void fauxSetMaximumPacketSize(int maximumPacketSize) {
  }
}
