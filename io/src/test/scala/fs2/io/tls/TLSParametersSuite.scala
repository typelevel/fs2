package fs2
package io
package tls

class TLSParametersSuite extends TLSSuite {
  group("toSSLParameters") {
    test("no client auth when wantClientAuth=false and needClientAuth=false") {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = false).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == false)
    }

    test("wantClientAuth when wantClientAuth=true and needClientAuth=false") {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = false).toSSLParameters
      assert(params.getWantClientAuth == true)
      assert(params.getNeedClientAuth == false)
    }

    test("needClientAuth when wantClientAuth=false and needClientAuth=true") {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = true).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == true)
    }

    test("needClientAuth when wantClientAuth=true and needClientAuth=true") {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = true).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == true)
    }
  }
}
