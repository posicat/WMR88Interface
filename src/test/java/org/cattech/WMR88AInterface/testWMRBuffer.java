package org.cattech.WMR88AInterface;

import static org.junit.Assert.assertEquals;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.cattech.WMR88Interface.WMRBuffer;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class testWMRBuffer {
	JSONCompareMode matchMode = JSONCompareMode.NON_EXTENSIBLE;

	@BeforeClass
	public static void setLogging() {
		Configurator.setRootLevel(Level.DEBUG);
	}

	@Test
	public void TestGetBits() {
		// Just a quick test to verify we're getting the bits correctly.
		byte[] testARR = { (byte) 0xFF, 0x00, (byte) 0xAA, 0x55 };

		WMRBuffer testBuf = new WMRBuffer(testARR);
		for (int i = 0; i < 8; i++) {
			// 1111 1111 - each bit
			assertEquals("bit " + i, 1, testBuf.getBits(0, i, 1));
			// 0000 0000 - each bit
			assertEquals("bit " + i, 0, testBuf.getBits(1, i, 1));
		}
		for (int i = 0; i < 8; i += 2) {
			// 1010 1010 - every other bit
			assertEquals("bit " + i, 0, testBuf.getBits(2, i, 1));
			// 0101 0101 - every other bit
			assertEquals("bit " + i, 1, testBuf.getBits(3, i, 1));
		}
		// 1111 1111 - lengths
		assertEquals("len 1", 1, testBuf.getBits(0, 0, 1));
		assertEquals("len 2", 3, testBuf.getBits(0, 0, 2));
		assertEquals("len 3", 7, testBuf.getBits(0, 0, 3));
		assertEquals("len 4", 15, testBuf.getBits(0, 0, 4));
		assertEquals("len 5", 31, testBuf.getBits(0, 0, 5));
		assertEquals("len 6", 63, testBuf.getBits(0, 0, 6));
		assertEquals("len 7", 127, testBuf.getBits(0, 0, 7));
		assertEquals("len 7", 255, testBuf.getBits(0, 0, 8));
		// 1010 1010 - lengths
		assertEquals("len 1", 0, testBuf.getBits(2, 0, 1));
		assertEquals("len 2", 2, testBuf.getBits(2, 0, 2));
		assertEquals("len 3", 2, testBuf.getBits(2, 0, 3));
		assertEquals("len 4", 10, testBuf.getBits(2, 0, 4));
		assertEquals("len 5", 10, testBuf.getBits(2, 0, 5));
		assertEquals("len 6", 42, testBuf.getBits(2, 0, 6));
		assertEquals("len 7", 42, testBuf.getBits(2, 0, 7));
		assertEquals("len 7", 170, testBuf.getBits(2, 0, 8));
	}

	@Test
	public void TestGetNibbles() {
		// Just a quick test to verify we're getting the nibbles correctly.
		byte[] testARR = {0x12,0x34,0x56,0x78};
		
		WMRBuffer testBuf = new WMRBuffer(testARR);
		
		assertEquals("byte 0 nib 0", 0x02, testBuf.getNibbles(0, 0, 1));
		assertEquals("byte 0 nib 1", 0x01, testBuf.getNibbles(0, 1, 1));
		assertEquals("byte 0 nib 0-1", 0x12, testBuf.getNibbles(0, 0, 2));

		assertEquals("byte 1 nib 0", 0x04, testBuf.getNibbles(1, 0, 1));
		assertEquals("byte 1 nib 1", 0x03, testBuf.getNibbles(1, 1, 1));
		assertEquals("byte 1 nib 0-1", 0x34, testBuf.getNibbles(1, 0, 2));

		// Nibbles read in the order of low,high,(next byte)low, high,(next byte)low, high...
		assertEquals("byte 0 nib 0",   0x02, testBuf.getNibbles(0, 0, 1));
		assertEquals("byte 0 nib 0-1", 0x12, testBuf.getNibbles(0, 0, 2));
		assertEquals("byte 0 nib 0-3", 0x0412, testBuf.getNibbles(0, 0, 3));
		assertEquals("byte 0 nib 0-4", 0x3412, testBuf.getNibbles(0, 0, 4));
		assertEquals("byte 0 nib 0-5", 0x063412, testBuf.getNibbles(0, 0, 5));
		assertEquals("byte 0 nib 0-6", 0x563412, testBuf.getNibbles(0, 0, 6));
		assertEquals("byte 0 nib 0-7", 0x08563412, testBuf.getNibbles(0, 0, 7));
	}
}
