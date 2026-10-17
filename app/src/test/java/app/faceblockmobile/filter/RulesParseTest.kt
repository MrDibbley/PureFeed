package app.faceblockmobile.filter

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RulesParseTest {

    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val shipped = File("../rules/rules.json").readText()

    @Test fun shippedRulesFileIsValid() {
        assertNotNull(RulesRepository.parse(shipped))
    }

    @Test fun wrongSchemaVersionIsRejected() {
        assertNull(RulesRepository.parse(shipped.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
    }

    @Test fun garbageIsRejected() {
        assertNull(RulesRepository.parse("<html>404</html>"))
        assertNull(RulesRepository.parse("{}"))
        assertNull(RulesRepository.parse("""{"schemaVersion":1,"rules":{},"units":{},"text":{}}"""))
    }

    @Test fun missingSectionIsRejected() {
        assertNull(RulesRepository.parse("""{"schemaVersion":1,"rules":{"a":{}},"text":{}}"""))
    }
}
