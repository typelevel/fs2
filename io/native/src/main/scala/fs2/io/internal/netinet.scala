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

import scalanative.unsafe._
import scalanative.posix.inttypes._
import scalanative.posix.sys.socket._

private[io] object netinetin {
  import Nat._
  type _16 = Digit2[_1, _6]

  type in_port_t = uint16_t

  type in_addr = CStruct1[uint32_t]

  type sockaddr_in = CStruct4[
    sa_family_t,
    in_port_t,
    in_addr,
    CArray[Byte, _8]
  ]

  type in6_addr = CStruct1[CArray[CUnsignedChar, _16]]

  type sockaddr_in6 = CStruct5[
    sa_family_t,
    in_port_t,
    uint32_t,
    in6_addr,
    uint32_t
  ]

}

private[io] object netinetinOps {
  import netinetin._

  implicit final class in_addrOps(val in_addr: in_addr) extends AnyVal {
    def s_addr: uint32_t = in_addr._1
    def s_addr_=(s_addr: uint32_t): Unit = in_addr._1 = s_addr
  }

  implicit final class sockaddr_inOps(val sockaddr_in: Ptr[sockaddr_in]) extends AnyVal {
    def sin_family: sa_family_t = sockaddr_in._1
    def sin_family_=(sin_family: sa_family_t): Unit = sockaddr_in._1 = sin_family
    def sin_port: in_port_t = sockaddr_in._2
    def sin_port_=(sin_port: in_port_t): Unit = sockaddr_in._2 = sin_port
    def sin_addr: in_addr = sockaddr_in._3
    def sin_addr_=(sin_addr: in_addr) = sockaddr_in._3 = sin_addr
  }

  implicit final class in6_addrOps(val in6_addr: in6_addr) extends AnyVal {
    def s6_addr: CArray[uint8_t, _16] = in6_addr._1
    def s6_addr_=(s6_addr: CArray[uint8_t, _16]): Unit = in6_addr._1 = s6_addr
  }

  implicit final class sockaddr_in6Ops(val sockaddr_in6: Ptr[sockaddr_in6]) extends AnyVal {
    def sin6_family: sa_family_t = sockaddr_in6._1
    def sin6_family_=(sin6_family: sa_family_t): Unit = sockaddr_in6._1 = sin6_family
    def sin6_port: in_port_t = sockaddr_in6._2
    def sin6_port_=(sin6_port: in_port_t): Unit = sockaddr_in6._2 = sin6_port
    def sin6_flowinfo: uint32_t = sockaddr_in6._3
    def sin6_flowinfo_=(sin6_flowinfo: uint32_t): Unit = sockaddr_in6._3 = sin6_flowinfo
    def sin6_addr: in6_addr = sockaddr_in6._4
    def sin6_addr_=(sin6_addr: in6_addr) = sockaddr_in6._4 = sin6_addr
    def sin6_scope_id: uint32_t = sockaddr_in6._5
    def sin6_scope_id_=(sin6_scope_id: uint32_t): Unit = sockaddr_in6._5 = sin6_scope_id
  }

}
