package epheis.lyphossyss.kysheon.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun millisecondsWithOneDecimalAndLocalSeparator() {
        assertEquals("34,8", formatMillis(34_750, ","))
        assertEquals("0.0", formatMillis(0, "."))
        assertEquals("-127,1", formatMillis(-127_080, ","))
        assertEquals("0,0", formatMillis(-40, ","), "no negative zero")
    }

    @Test
    fun oneDecimal() {
        assertEquals("11,4", formatOneDecimal(11.38, ","))
        assertEquals("-22.2", formatOneDecimal(-22.24, "."))
        assertEquals("0,0", formatOneDecimal(-0.01, ","))
    }
}
