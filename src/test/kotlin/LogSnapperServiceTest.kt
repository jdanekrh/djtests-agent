import org.junit.Assert.assertEquals
import org.junit.Test

class LogSnapperServiceTest {
    @Test
    fun findStringsInStream() {
        val lss = LogSnapperService()

        data class T(val strings: List<String>, val horizon: Int, val string: String, val expected: Boolean)
        for (t in listOf(
                T(listOf("b", "a"), 20, "ab", false),
                T(listOf("a", "b"), 2, "aab", false),
                T(listOf("a", "b"), 3, "aab", true)
        )) {
            val result = lss.findStringsInStream(t.strings, t.horizon, t.string.byteInputStream())
            assertEquals(t.expected, result)
        }
    }
}