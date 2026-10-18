package app.purefeed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun versionsCompareNumerically() {
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.1", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0.2") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
    }

    @Test fun suffixesAreIgnored() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.1-debug", "1.0.1"))
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0.0-test") > 0)
    }

    @Test fun parsesGitHubRelease() {
        val json = """{"tag_name":"v1.0.2","html_url":"https://github.com/MrDibbley/PureFeed/releases/tag/v1.0.2",
            "body":"Fixes","assets":[
              {"name":"SHA256SUMS.txt","size":85,"browser_download_url":"https://example/SHA256SUMS.txt"},
              {"name":"PureFeed-1.0.2.apk","size":9243957,"browser_download_url":"https://example/PureFeed-1.0.2.apk"}]}"""
        val r = UpdateChecker.parseRelease(json)!!
        assertEquals("1.0.2", r.version)
        assertEquals("PureFeed-1.0.2.apk", r.apkName)
        assertEquals(9243957L, r.apkSize)
        assertEquals("https://example/SHA256SUMS.txt", r.sumsUrl)
        assertTrue(UpdateChecker.isNewer(r, "1.0.1"))
        assertFalse(UpdateChecker.isNewer(r, "1.0.2"))
    }

    @Test fun releaseWithoutApkIsIgnored() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v2.0.0","assets":[]}"""))
    }

    @Test fun readsShaFromSumsFile() {
        val sums = "43c9dd7901e16dbe777438513911ec871ac58a4d12a9b1b4b7c1c03f234d9618  PureFeed-1.0.1.apk\n"
        assertEquals("43c9dd7901e16dbe777438513911ec871ac58a4d12a9b1b4b7c1c03f234d9618",
            UpdateChecker.shaFor(sums, "PureFeed-1.0.1.apk"))
        assertNull(UpdateChecker.shaFor(sums, "other.apk"))
        assertNull(UpdateChecker.shaFor("nothex  PureFeed-1.0.1.apk", "PureFeed-1.0.1.apk"))
    }
}
