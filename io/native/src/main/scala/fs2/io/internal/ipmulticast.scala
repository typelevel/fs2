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

import scala.scalanative.unsafe._
import netinetin.in_addr
import netinetin.in6_addr
import scala.scalanative.meta.LinktimeInfo

private[io] object Ipmulticast {
  private val isMac = LinktimeInfo.isMac

  val IP_MULTICAST_TTL: CInt =
    if (isMac) 10
    else 33
  val IP_MULTICAST_LOOP: CInt =
    if (isMac) 11
    else 34

  val IP_ADD_MEMBERSHIP: CInt =
    if (isMac) 12
    else 35

  val IP_DROP_MEMBERSHIP: CInt = 13

  val IP_ADD_SOURCE_MEMBERSHIP: CInt =
    if (isMac) 0x46 else 39

  val IP_DROP_SOURCE_MEMBERSHIP: CInt =
    if (isMac) 0x47 else 40

  val IP_BLOCK_SOURCE: CInt =
    if (isMac) 0x48 else 38

  val IP_UNBLOCK_SOURCE: CInt =
    if (isMac) 0x49 else 37

  val IPV6_MULTICAST_LOOP: CInt =
    if (isMac) 11
    else 19

  val IPV6_ADD_MEMBERSHIP: CInt =
    if (isMac) 12
    else 20

  val IPV6_DROP_MEMBERSHIP: CInt =
    if (isMac) 13
    else 21

  type ip_mreq = CStruct2[
    in_addr,
    in_addr
  ]

  type ip_mreq_source = CStruct3[
    in_addr,
    in_addr,
    in_addr
  ]

  type ipv6_mreq = CStruct2[
    in6_addr,
    CInt
  ]
}

private[io] object IpmulticastOps {
  import Ipmulticast._

  implicit final class ip_mreqOps(val ip_mreq: Ptr[ip_mreq]) extends AnyVal {
    def imr_multiaddr: in_addr = ip_mreq._1
    def imr_multiaddr_=(imr_multiaddr: in_addr): Unit = ip_mreq._1 = imr_multiaddr
    def imr_address: in_addr = ip_mreq._2
    def imr_address_=(imr_address: in_addr): Unit = ip_mreq._2 = imr_address
  }

  implicit final class ip_mreq_sourceOps(val mreq: Ptr[ip_mreq_source]) extends AnyVal {
    def imr_multiaddr: in_addr = mreq._1
    def imr_multiaddr_=(imr_multiaddr: in_addr): Unit = mreq._1 = imr_multiaddr

    def imr_interface: in_addr = mreq._2
    def imr_interface_=(imr_interface: in_addr): Unit = mreq._2 = imr_interface

    def imr_sourceaddr: in_addr = mreq._3
    def imr_sourceaddr_=(imr_sourceaddr: in_addr): Unit = mreq._3 = imr_sourceaddr
  }

  implicit final class ipv6_mreqOps(val mreq: Ptr[ipv6_mreq]) extends AnyVal {
    def ipv6mr_multiaddr: in6_addr = mreq._1
    def ipv6mr_multiaddr_=(ipv6mr_multiaddr: in6_addr): Unit = mreq._1 = ipv6mr_multiaddr

    def ipv6mr_ifindex: CInt = mreq._2
    def ipv6mr_ifindex_=(ipv6mr_ifindex: CInt): Unit = mreq._2 = ipv6mr_ifindex
  }

}
