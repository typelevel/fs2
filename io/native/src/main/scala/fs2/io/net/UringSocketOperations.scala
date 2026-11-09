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
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package fs2
package io
package net

import fs2.io.internal.NativeUtil.errnoToThrowable

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[net] object UringSocketOperations {

  private final val IORING_OP_ACCEPT = 13
  private final val IORING_OP_CONNECT = 16
  private final val IORING_OP_CLOSE = 19
  private final val IORING_OP_SEND = 26
  private final val IORING_OP_RECV = 27
  private final val IORING_OP_SOCKET = 45

  private type U8 = CUnsignedChar
  private type U16 = CUnsignedShort
  private type S32 = CInt
  private type U32 = CUnsignedInt
  private type U64 = CUnsignedLongLong

  type io_uring_sqe = CStruct10[
    U8,
    U8,
    U16,
    S32,
    U64,
    U64,
    U32,
    U32,
    U64,
    CArray[U64, Nat._3]
  ]

  def io_uring_prep_socket(
      sqe: Ptr[io_uring_sqe],
      domain: Int,
      socketType: Int,
      protocol: Int,
      flags: Int
  ): Unit = {
    val typed =
      io_uring_prep_rw(IORING_OP_SOCKET, sqe, domain, null, protocol.toUInt, socketType.toULong)
    !typed.at8 = flags.toUInt
  }

  def io_uring_prep_connect(
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      address: Ptr[?],
      addressLength: CUnsignedInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_CONNECT, sqe, fd, address, 0.toUInt, addressLength.toULong)
    ()
  }

  def io_uring_prep_accept(
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      address: Ptr[?],
      addressLength: Ptr[CUnsignedInt],
      flags: Int
  ): Unit = {
    val typed = io_uring_prep_rw(
      IORING_OP_ACCEPT,
      sqe,
      fd,
      address,
      0.toUInt,
      pointerValue(addressLength)
    )
    !typed.at8 = flags.toUInt
  }

  def io_uring_prep_recv(
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      buffer: Ptr[Byte],
      length: Int,
      flags: Int
  ): Unit = {
    val typed = io_uring_prep_rw(IORING_OP_RECV, sqe, fd, buffer, length.toUInt, 0.toULong)
    !typed.at8 = flags.toUInt
  }

  def io_uring_prep_send(
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      buffer: Ptr[Byte],
      length: Int,
      flags: Int
  ): Unit = {
    val typed = io_uring_prep_rw(IORING_OP_SEND, sqe, fd, buffer, length.toUInt, 0.toULong)
    !typed.at8 = flags.toUInt
  }

  def io_uring_prep_close(sqe: Ptr[io_uring_sqe], fd: Int): Unit = {
    io_uring_prep_rw(IORING_OP_CLOSE, sqe, fd, null, 0.toUInt, 0.toULong)
    ()
  }

  def checkResult(result: Int): Int =
    if (result < 0) throw errnoToThrowable(-result)
    else result

  private def io_uring_prep_rw(
      opcode: Int,
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      address: Ptr[?],
      length: CUnsignedInt,
      offset: U64
  ): Ptr[io_uring_sqe] = {
    !sqe.at1 = opcode.toUByte
    !sqe.at2 = 0.toUByte
    !sqe.at3 = 0.toUShort
    !sqe.at4 = fd
    !sqe.at5 = offset
    !sqe.at6 = pointerValue(address)
    !sqe.at7 = length
    !sqe.at8 = 0.toUInt
    sqe._10(0) = 0.toULong
    sqe._10(1) = 0.toULong
    sqe._10(2) = 0.toULong
    sqe
  }

  private def pointerValue(pointer: Ptr[?]): U64 =
    if (pointer eq null) 0.toULong
    else pointer.toLong.toULong
}
