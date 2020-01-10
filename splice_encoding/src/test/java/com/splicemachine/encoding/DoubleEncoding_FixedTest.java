/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.encoding;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/*
 * Test DoubleEncoding with specific (aka fixed) values.
 */
public class DoubleEncoding_FixedTest {

    private BitFormat bitFormat = new BitFormat(false);

    /* Note that the encodings of positive and negative zero both contain multiple occurrences of 0x0. */
    @Test
    public void testEncodingOfPositiveAndNegativeZero() throws Exception {
        double zero = 0.0;
        double negativeZero = -0.0;

        // IEEE 754 encoding
        assertEquals("00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000", bitFormat.format(Double.doubleToLongBits(zero)));
        assertEquals("10000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000", bitFormat.format(Double.doubleToLongBits(negativeZero)));

        // splice encoding
        assertEquals("[-128, 0, 0, 0, 0, 0, 0, 1]", Arrays.toString(DoubleEncoding.toBytes(zero, false)));
        assertEquals("[-128, 0, 0, 0, 0, 0, 0, 0]", Arrays.toString(DoubleEncoding.toBytes(negativeZero, false)));
        assertEquals("[127, -1, -1, -1, -1, -1, -1, -2]", Arrays.toString(DoubleEncoding.toBytes(zero, true)));
        assertEquals("[127, -1, -1, -1, -1, -1, -1, -1]", Arrays.toString(DoubleEncoding.toBytes(negativeZero, true)));
    }

}
