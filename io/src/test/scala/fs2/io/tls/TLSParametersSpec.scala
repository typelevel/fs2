package fs2
package io
package tls

class TLSParametersSpec extends TLSSpec {
  "toSSLParameters" - {
    "no client auth when wantClientAuth=false and needClientAuth=false" in {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = false).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == false)
    }

    "wantClientAuth when wantClientAuth=true and needClientAuth=false" in {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = false).toSSLParameters
      assert(params.getWantClientAuth == true)
      assert(params.getNeedClientAuth == false)
    }

    "needClientAuth when wantClientAuth=false and needClientAuth=true" in {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = true).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == true)
    }

    "needClientAuth when wantClientAuth=true and needClientAuth=true" in {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = true).toSSLParameters
      assert(params.getWantClientAuth == false)
      assert(params.getNeedClientAuth == true)
    }
  }
}
