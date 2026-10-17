package app.purefeed.web

import app.purefeed.web.UrlPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPolicyTest {

    @Test fun facebookStaysInternal() {
        assertEquals(Decision.Internal, UrlPolicy.decide("https://m.facebook.com/home.php"))
        assertEquals(Decision.Internal, UrlPolicy.decide("https://www.facebook.com/groups/123"))
        assertEquals(Decision.Internal, UrlPolicy.decide("https://scontent.xx.fbcdn.net/v/photo.jpg"))
    }

    @Test fun lookalikeDomainIsExternal() {
        val url = "https://notfacebook.com/x"
        assertEquals(Decision.External(url), UrlPolicy.decide(url))
    }

    @Test fun otherSitesAreExternal() {
        val url = "https://www.bbc.co.uk/news"
        assertEquals(Decision.External(url), UrlPolicy.decide(url))
    }

    @Test fun linkShimOpensRealTargetExternally() {
        val shim = "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fpage%3Fa%3D1&h=AT0abc"
        assertEquals(Decision.External("https://example.org/page?a=1"), UrlPolicy.decide(shim))
    }

    @Test fun linkShimToFacebookStaysInternal() {
        val shim = "https://lm.facebook.com/l.php?u=https%3A%2F%2Fm.facebook.com%2Fevents%2F1"
        assertEquals(Decision.Internal, UrlPolicy.decide(shim))
    }

    @Test fun appDeepLinksAreBlocked() {
        assertEquals(Decision.Block, UrlPolicy.decide("fb://profile/123"))
        assertEquals(Decision.Block, UrlPolicy.decide("intent://foo#Intent;scheme=fb;end"))
    }

    @Test fun mailtoIsExternal() {
        assertEquals(Decision.External("mailto:a@b.c"), UrlPolicy.decide("mailto:a@b.c"))
    }
}
