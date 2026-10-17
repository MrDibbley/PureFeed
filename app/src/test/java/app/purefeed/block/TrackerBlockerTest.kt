package app.purefeed.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackerBlockerTest {

    private val domains = setOf("doubleclick.net", "google-analytics.com")

    @Test fun exactAndSubdomainsMatch() {
        assertTrue(TrackerBlocker.matches(domains, "doubleclick.net"))
        assertTrue(TrackerBlocker.matches(domains, "stats.g.doubleclick.net"))
        assertTrue(TrackerBlocker.matches(domains, "WWW.Google-Analytics.com."))
    }

    @Test fun lookalikesDoNotMatch() {
        assertFalse(TrackerBlocker.matches(domains, "notdoubleclick.net"))
        assertFalse(TrackerBlocker.matches(domains, "doubleclick.net.example.org"))
        assertFalse(TrackerBlocker.matches(domains, null))
    }

    @Test fun facebookIsNeverBlocked() {
        val bad = setOf("facebook.com", "fbcdn.net")
        assertFalse(TrackerBlocker.matches(bad, "m.facebook.com"))
        assertFalse(TrackerBlocker.matches(bad, "scontent-man2-1.xx.fbcdn.net"))
    }

    @Test fun normaliseCleansUserInput() {
        assertEquals("example.com", TrackerBlocker.normalise(" https://Example.com/path "))
        assertEquals("example.com", TrackerBlocker.normalise("*.example.com"))
        assertNull(TrackerBlocker.normalise("localhost"))
        assertNull(TrackerBlocker.normalise("bad domain.com"))
    }

    @Test fun shippedListParses() {
        val list = TrackerBlocker.parseList(File("src/main/assets/blocklist.txt").readText())
        assertTrue(list.size > 20)
        assertTrue("doubleclick.net" in list)
        assertTrue(list.none { it.startsWith("#") })
    }
}
