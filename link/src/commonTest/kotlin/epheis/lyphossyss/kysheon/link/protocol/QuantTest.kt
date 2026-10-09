package epheis.lyphossyss.kysheon.link.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuantTest {

    @Test
    fun yCoversTheFullRangeAndClamps() {
        assertEquals(0, Quant.encodeY(0.0))
        assertEquals(0xFFFF, Quant.encodeY(1.0))
        assertEquals(0, Quant.encodeY(-0.5))
        assertEquals(0xFFFF, Quant.encodeY(3.0))
        assertEquals(1.0, Quant.decodeY(0xFFFF))
    }

    @Test
    fun yErrorStaysBelowHalfAStep() {
        var y = 0.0
        while (y <= 1.0) {
            assertTrue(abs(Quant.decodeY(Quant.encodeY(y)) - y) <= 0.5 / Quant.Y_SCALE + 1e-12)
            y += 0.0137
        }
    }

    @Test
    fun velocityRoundsToThousandthsAndClamps() {
        assertEquals(1234, Quant.encodeVelocity(1.2344))
        assertEquals(-1235, Quant.encodeVelocity(-1.2346))
        assertEquals(Short.MAX_VALUE.toInt(), Quant.encodeVelocity(100.0))
        assertEquals(Short.MIN_VALUE.toInt(), Quant.encodeVelocity(-100.0))
        assertEquals(1.234, Quant.decodeVelocity(1234))
    }

    @Test
    fun aspectStaysWithinU16() {
        assertEquals(2222, Quant.encodeAspect(2.2222))
        assertEquals(1, Quant.encodeAspect(0.0))
        assertEquals(0xFFFF, Quant.encodeAspect(1000.0))
    }

    @Test
    fun rejectsNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> { Quant.encodeY(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Quant.encodeVelocity(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Quant.encodeAspect(Double.NaN) }
    }
}
