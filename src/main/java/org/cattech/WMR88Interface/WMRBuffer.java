package org.cattech.WMR88Interface;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class WMRBuffer extends ArrayList<Byte> {
	private static final long serialVersionUID = 1L;
	Logger log = LogManager.getLogger(WMR88InterfaceThread.class);

	public WMRBuffer() {
		this.clear();
	}

	public WMRBuffer(List<Byte> subList) {
		this.clear();
		this.append(subList);
	}

	public WMRBuffer(byte[] arrFF) {
		for (byte b : arrFF) {
			this.add(b);
		}
	}

	private void debugDumpBuffer(String string, List<Byte> data) {
		debugDumpBuffer(string, new WMRBuffer(data));
	}

	
	private void debugDumpBuffer(String string, byte[] data) {
		debugDumpBuffer(string, new WMRBuffer(data));
	}

	private void debugDumpBuffer(String string, WMRBuffer data) {
		log.debug(string + data.toString());
	}

	private void append(List<Byte> subList) {
		this.addAll(subList);
	}

	public void append(int responseByteCount, byte[] data) {
		int count = data[0]; // First byte is the number of expected bytes in this packet.

		if (responseByteCount > 0 & count > 0) {
			if (count < data.length) {
				// go through remaining response bytes and copy USB to station buffer (starting
				// at 1, the byte after the length)
				for (int i = 1; i < count + 1; i++) {
					this.add(data[i]);
				}
			} else {
				log.error("Length of packet : " + count + ", longer than buffer length " + data.length);
			}
		}
	}

	public void prepend(List<Byte> data) {
		int offset = 0;
		for (Byte b : data) {
			this.add(offset++, b);
		}
	}

	private String implToStringFormats(boolean hexPrefix) {
		String result = "";
		for (byte b : this) {
			if (!result.equals("")) {
				result += ",";
			}
			if (hexPrefix) {
				result += String.format("0x%02X", b);
			} else {
				result += String.format("%02X", b);
			}
		}
		return result;
	}

	public String toJavaArray() {
		return ("{" + this.implToStringFormats(true) + "}");
	}

	@Override
	public String toString() {
		return this.implToStringFormats(false);
	}

	public String toStringAndLength() {
		return this.toString() + " [" + this.size() + " bytes]";
	}

	@Override
	@Deprecated
	/**
	 * WMR Buffer has more precise methods to get bits, bytes and words, use those.
	 */
	public Byte get(int i) {
		throw new UnsupportedOperationException("Don't use plain get, use getByte or more specific methods");
	}

	// Preferred methods to use

	private boolean getBit(int byteOffset, int bitOffset) {
		return (this.getByte(byteOffset) & 2 ^ bitOffset) != 0;
	}

	public int getBits(int byteOffset, int bitOffset, int numBits) {
		int bits = 0;
		for (int i = (bitOffset + numBits) - 1; i >= bitOffset; i--) {
			int t = (this.getByte(byteOffset) & (1 << i)) / (1 << i);
			bits = (bits << 1) + t;
		}
		return bits;
	}

	public int getNibble(int bytePos, int nibbleOffset) {
		return this.getBits(bytePos, nibbleOffset * 4, 4);
	}

	public int getNibbles(int bytePos, int nibbleOffset, int nibbleNum) {
		int nibbles = 0;
		for (int i = 0; i < nibbleNum; i++) {
			int curBytePos = bytePos + (nibbleOffset + i) / 2;
			int nibblePos = (nibbleOffset + i) % 2;
			nibbles = nibbles + (1<<(4*i)) * this.getNibble(curBytePos, nibblePos);
		}
		return nibbles;
	}

	public int getByte(int bytePos) {
		return Byte.toUnsignedInt(super.get(bytePos));
	}

	public int getWord(int i) {
		return 256 * this.getByte(i + 1) + this.getByte(i);
	}

	// String helper methods

	public String getBitAsString(int byteOffset, int bitOffset, String if0, String if1) {
		return this.getBit(byteOffset, bitOffset) ? if1 : if0;
	}

	public String getBitsAsString(int byteOffset, int bitOffset, int numBits, String[] choices) {
		if ((1 << numBits) > choices.length) {
			log.warn("Array length " + choices.length + " passed is too short for the maximum possible bit range of " + numBits + "bits");
		}
		int idx = this.getBits(byteOffset, bitOffset, numBits);
		return choices[idx];
	}

}
