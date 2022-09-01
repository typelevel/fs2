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

package fs2.io.net.tls

import scala.scalanative.unsafe._
import scala.annotation.nowarn

@extern
@link("s2n")
@nowarn
private[tls] object s2n {

  final val S2N_SUCCESS = 0
  final val S2N_FAILURE = -1
  final val S2N_CALLBACK_BLOCKED = -2

  type s2n_error_type = CUnsignedInt
  final val S2N_ERR_T_BLOCKED = 3

  type s2n_mode = CUnsignedInt
  final val S2N_SERVER = 0
  final val S2N_CLIENT = 1

  type s2n_blocked_status = CUnsignedInt
  final val S2N_NOT_BLOCKED = 0
  final val S2N_BLOCKED_ON_READ = 1
  final val S2N_BLOCKED_ON_WRITE = 2
  final val S2N_BLOCKED_ON_APPLICATION_INPUT = 3
  final val S2N_BLOCKED_ON_EARLY_DATA = 4

  type s2n_config
  type s2n_connection

  type s2n_recv_fn = CFuncPtr3[Ptr[Byte], Ptr[Byte], CUnsignedInt, CInt]
  type s2n_send_fn = CFuncPtr3[Ptr[Byte], Ptr[Byte], CUnsignedInt, CInt]

  def s2n_errno_location(): Ptr[CInt] = extern

  def s2n_error_get_type(error: CInt): CInt = extern

  def s2n_init(): CInt = extern

  def s2n_cleanup(): CInt = extern

  def s2n_config_new(): Ptr[s2n_config] = extern

  def s2n_config_free(config: Ptr[s2n_config]): CInt = extern

  def s2n_config_disable_x509_verification(config: Ptr[s2n_config]): CInt = extern

  def s2n_strerror(error: CInt, lang: Ptr[CChar]): Ptr[CChar] = extern

  def s2n_connection_new(mode: s2n_mode): Ptr[s2n_connection] = extern

  def s2n_connection_set_config(conn: Ptr[s2n_connection], config: Ptr[s2n_config]): CInt = extern

  def s2n_connection_set_recv_ctx(conn: Ptr[s2n_connection], ctx: Ptr[Byte]): CInt = extern

  def s2n_connection_set_send_ctx(conn: Ptr[s2n_connection], ctx: Ptr[Byte]): CInt = extern

  def s2n_connection_set_recv_cb(conn: Ptr[s2n_connection], recv: s2n_recv_fn): CInt = extern

  def s2n_connection_set_send_cb(conn: Ptr[s2n_connection], send: s2n_recv_fn): CInt = extern

  def s2n_set_server_name(conn: Ptr[s2n_connection], server_name: Ptr[CChar]): CInt = extern

  def s2n_get_application_protocol(conn: Ptr[s2n_connection]): Ptr[CChar] = extern

  def s2n_negotiate(conn: Ptr[s2n_connection], blocked: Ptr[s2n_blocked_status]): CInt = extern

  def s2n_send(
      conn: Ptr[s2n_connection],
      buf: Ptr[Byte],
      size: CSSize,
      blocked: Ptr[s2n_blocked_status]
  ): CInt = extern

  def s2n_recv(
      conn: Ptr[s2n_connection],
      buf: Ptr[Byte],
      size: CSSize,
      blocked: Ptr[s2n_blocked_status]
  ): CInt = extern

  def s2n_peek(conn: Ptr[s2n_connection]): CUnsignedInt = extern

  def s2n_connection_free_handshake(conn: Ptr[s2n_connection]): CInt = extern

  def s2n_connection_free(conn: Ptr[s2n_connection]): CInt = extern

  def s2n_shutdown(conn: Ptr[s2n_connection], blocked: Ptr[s2n_blocked_status]): CInt =
    extern

  def s2n_connection_get_session(
      conn: Ptr[s2n_connection],
      session: Ptr[Byte],
      maxLength: CSize
  ): CInt = extern

  def s2n_connection_get_session_length(conn: Ptr[s2n_connection]): CInt = extern

}
