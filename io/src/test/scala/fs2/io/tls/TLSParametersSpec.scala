package fs2
package io
package tls

class TLSParametersSpec extends TLSSpec {
  "toSSLParameters" - {
    "no client auth when wantClientAuth=false and needClientAuth=false" in {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = false).toSSLParameters
      params.getWantClientAuth shouldBe false
      params.getNeedClientAuth shouldBe false
    }

    "wantClientAuth when wantClientAuth=true and needClientAuth=false" in {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = false).toSSLParameters
      params.getWantClientAuth shouldBe true
      params.getNeedClientAuth shouldBe false
    }

    "needClientAuth when wantClientAuth=false and needClientAuth=true" in {
      val params = TLSParameters(wantClientAuth = false, needClientAuth = true).toSSLParameters
      params.getWantClientAuth shouldBe false
      params.getNeedClientAuth shouldBe true
    }

    "needClientAuth when wantClientAuth=true and needClientAuth=true" in {
      val params = TLSParameters(wantClientAuth = true, needClientAuth = true).toSSLParameters
      params.getWantClientAuth shouldBe false
      params.getNeedClientAuth shouldBe true
    }
  }
}
