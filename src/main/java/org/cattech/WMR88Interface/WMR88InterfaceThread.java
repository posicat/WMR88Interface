package org.cattech.WMR88Interface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.codeminders.hidapi.HIDDevice;
import com.codeminders.hidapi.HIDManager;

public class WMR88InterfaceThread implements Runnable {
	Logger log = LogManager.getLogger(WMR88InterfaceThread.class);

	private final static String[] DIRECTION_DESCRIPTION = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
	private final static String[] WEATHER_DESCRIPTION = { "Partly Cloudy", "Rainy", "Cloudy", "Sunny", "?", "Snowy" };
	private final static String[] TREND_DESCRIPTION = { "Stable", "Rising", "Falling", "Unknown" };
	private final static String[] MOOD_FACES = { "", ":-)", ":-(", ":-|" };

	final int CENTURY = 2000;

	private final static byte[] STATION_INITIALISATION_WMR200 = { (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
	private final static byte[] STATION_REQUEST_WMR200 = { (byte) 0x00, (byte) 0x01, (byte) 0xD0, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

	private final static int FRAME_BYTE_DELIMITER = (byte) 0xFF;

	// Time from last sensor data or data request before requesting data again
	// (sec); this should be more than 60 seconds (the normal response interval of a
	// WMR100)
	private final int STATION_TIMEOUT_BEFORE_REREQUEST_SEC = 90;

	// ----------------------------- USB variables ------------------------------

	/** Weather station USB vendor/product identifiers */
	private final int STATION_PRODUCT = 0xCA01;
	private final int STATION_VENDOR = 0x0FDE;

	private HIDManager hidManager;
	private HIDDevice hidDevice;

	private long lastDataReceivedMS;
	private final int RESPONSE_TIMEOUT_SEC = 10;

//	private final int STATION_BUFFER_BYTES = 100;
	private final int BUFFER_USB_RESP0NSE_BYTES = 9;

//	private int stationBufferIndexNext;
//	private byte[] stationBuffer = new byte[STATION_BUFFER_BYTES];
	private ArrayList<Byte> stationBuffer = new ArrayList<Byte>();

	Calendar c = Calendar.getInstance();

	private boolean running;
	private WMR88Callback callback;

	private boolean returnInvalidFrames = false;
	private boolean useMetric = false;
	private boolean overrideTimezone = true;
	// TODO Add option to include units on all values

	/**
	 * Main thread to open the weather station device, repeatedly read from it, and
	 * route data as required.
	 **/
	@Override
	public void run() {
		this.running = true;

		log.info("Initializing sensor data");

		lastDataReceivedMS = 0;
		stationBuffer.clear();

		byte[] responseBufferUSB = new byte[BUFFER_USB_RESP0NSE_BYTES];

		try {
			com.codeminders.hidapi.ClassPathLibraryLoader.loadNativeHIDLibrary();
			hidManager = HIDManager.getInstance();
			hidDevice = stationOpen();

			if (hidDevice == null) {
				throw (new Exception("could not open weather station device"));
			} else {
				while (running) {
					if (System.currentTimeMillis() - lastDataReceivedMS > STATION_TIMEOUT_BEFORE_REREQUEST_SEC * 1000) {
						stationDataRequest();
						lastDataReceivedMS = System.currentTimeMillis();
					}

					int responseByteCount = hidDevice.readTimeout(responseBufferUSB, RESPONSE_TIMEOUT_SEC * 1000);
					appendDataToStationBuffer(responseByteCount, responseBufferUSB);
					parseStationData();
				}
			}

		} catch (Throwable throwable) {
			log.error("Thread error: " + throwable);
			throwable.printStackTrace();

			this.running = false;
		} finally {
			stationCloseNoThrow();
		}
	}

	public JSONObject analyseSensorDataFrame(ArrayList<Byte> frameBuffer) throws IOException {
		JSONObject decoded = new JSONObject();

		DeviceParameters devParm = DeviceParameters.lookup(frameBuffer.get(1));

		switch (devParm) {
		case Anemometer:
			decodeAnemometer(decoded, frameBuffer);
			break;
		case Barometer:
			decodeBarometer(decoded, frameBuffer);
			break;
		case Clock:
			decodeClock(decoded, frameBuffer);
			break;
		case Rainfall:
			decodeRainfall(decoded, frameBuffer);
			break;
		case Thermohygrometer:
			decodeThermohygrometer(decoded, frameBuffer);
			break;
		case UV:
			decodeUV(decoded, frameBuffer);
			break;
		case INVALID:
			decoded.put("Error", "Received packet for unknown sensor ID : code 0x" + String.format("%02X", frameBuffer.get(1)));
		}

		if (decoded.has("Error")) {
			log.debug("Ignoring invalid frame " + dumpFrameInformation(frameBuffer));
			if (returnInvalidFrames) {
				decoded.put("Type", "InvalidFrame");
				decoded.put("Frame", bytesToString(frameBuffer, false));
			} else {
				decoded = new JSONObject();
			}
		} else {
			// Keep track of the last received valid packet, so we can timeout and
			// re-request from the station.
			lastDataReceivedMS = System.currentTimeMillis();
		}

		if (callback != null) {
			callback.receiveData(decoded.toString());
		}

		generateTestCode(frameBuffer, decoded); // Convenience method for adding tests quickly.
		return decoded;

	}

	private void generateTestCode(List<Byte> frameBuffer, JSONObject decoded) {
		// Generate test Conditions
		System.out.println("@Test\npublic void testDecode_() throws IOException {");
		System.out.println("	WMR88InterfaceThread it = new WMR88InterfaceThread();");
		System.out.println("	byte[] frame = {" +

				bytesToString(frameBuffer, true) + "};");
		System.out.println("	JSONObject expected = new JSONObject(\"" + decoded.toString().replaceAll("[\"]", "\\\\\"") + "\");");
		System.out.println("	JSONObject actual = it.analyseSensorDataFrame(frame);");
		System.out.println("	JSONAssert.assertEquals(expected,actual,false);");
		System.out.println("}");
	}

	private void decodeClock(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "Clock");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Clock)) {
			bitDecode(decoded, frameBuffer.get(0), 7, "Powered", "Yes", "No");
			bitDecode(decoded, frameBuffer.get(0), 6, "Battery", "Good", "Low");
			bitDecode(decoded, frameBuffer.get(0), 5, "RFSync", "Inactive", "Active");
			bitDecode(decoded, frameBuffer.get(0), 4, "RFSignal", "Strong", "Weak/Searching"); // TODO Verify this with the display,
			long clockMillis = dataDecodeClock(decoded, frameBuffer, 4, true);

			long deltaMillis = System.currentTimeMillis() - clockMillis;
			decoded.put("deltaMilis", deltaMillis);
			log.info("Computer time and Station time differ by " + deltaMillis + "ms");
		}
	}

	private long dataDecodeClock(JSONObject decoded, List<Byte> frameBuffer, int i, boolean containsTZ) {
		c.set(frameBuffer.get(i + 4) + CENTURY, frameBuffer.get(i + 3) - 1, frameBuffer.get(i + 2), frameBuffer.get(i + 1), frameBuffer.get(i), 0);

		// If we allow the timezone to be overridden by the Station, grab and set it
		// here.
		// Note the WMR88A does not return the right timezone, so by default this is
		// turned off.
		if (containsTZ && !overrideTimezone) {
			TimeZone tz = TimeZone.getDefault();
			byte offset = frameBuffer.get(9);
			tz.setID("UTC +" + offset);
			tz.setRawOffset(1000 * 60 * 60 * offset);
			c.setTimeZone(tz);
		}

		decoded.put("DateTime", c.getTime().toString());
		long timeInMillis = c.getTimeInMillis() / 1000 * 1000;
		decoded.put("Timestamp", timeInMillis); // Zero out any milliseconds as the clock doesn't even report seconds.

		return timeInMillis;
	}

	private void decodeBarometer(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "Barometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Barometer)) {
			decoded.put("pressureAbsolute", 256 * (Byte.toUnsignedInt(frameBuffer.get(3)) % 16) + Byte.toUnsignedInt(frameBuffer.get(2)));
			decoded.put("pressureRelative", 256 * (Byte.toUnsignedInt(frameBuffer.get(5)) % 16) + Byte.toUnsignedInt(frameBuffer.get(4)));
			decoded.put("weatherForcast", getWeatherDescription(Byte.toUnsignedInt(frameBuffer.get(3)) / 16));
			decoded.put("weatherPrevious", getWeatherDescription(Byte.toUnsignedInt(frameBuffer.get(5)) / 16));
		}
	}

	private String getWeatherDescription(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION.length ? WEATHER_DESCRIPTION[weatherCode] : "Unknown");
	}

	private void decodeUV(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "UV");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.UV)) {
			highNibbleDecodeBattery(decoded, frameBuffer.get(0), "Battery");
			byteDecode(decoded, frameBuffer.get(3), "UV_Index");
		}
	}

	private void decodeThermohygrometer(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "Thermohygrometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Thermohygrometer)) {

			bitDecode(decoded, frameBuffer.get(0), 6, "Battery", "Low", "OK");
			twoBitDecode(decoded, frameBuffer.get(0), 4, "TemperatureTrend", TREND_DESCRIPTION);
			twoBitDecode(decoded, frameBuffer.get(2), 0, "HumidityTrend", TREND_DESCRIPTION);
			twoBitDecode(decoded, frameBuffer.get(2), 0, "Mood", MOOD_FACES);
			lowNibbleDecode(decoded, frameBuffer.get(2), "SensorNumber");

			int temperatureSign = getSign(Byte.toUnsignedInt(frameBuffer.get(4)) / 16);
			float temperature = temperatureSign * (256.0f * (Byte.toUnsignedInt(frameBuffer.get(4)) % 16) + Byte.toUnsignedInt(frameBuffer.get(3))) / 10.0f;
			decoded.put("Temperature", String.format("%.1f", temperature));

			int dewpointSign = getSign(Byte.toUnsignedInt(frameBuffer.get(7)) / 16);
			float dewpoint = dewpointSign * (256.0f * (Byte.toUnsignedInt(frameBuffer.get(7)) % 16) + Byte.toUnsignedInt(frameBuffer.get(6))) / 10.0f;
			decoded.put("DewPoint", String.format("%.1f", dewpoint));

			byteDecode(decoded, frameBuffer.get(5), "Humidity");

			decodeWindChillHeatIndex(decoded, frameBuffer);
		}
	}

	private void decodeWindChillHeatIndex(JSONObject decoded, List<Byte> frameBuffer) {
		int dewpointSign = getSign(Byte.toUnsignedInt(frameBuffer.get(7)) / 16);

		float heatIndex = 0;
		if (useMetric) {
			heatIndex = dewpointSign * convertFahrenheitToCelsius((256.0f * (Byte.toUnsignedInt((byte) (frameBuffer.get(9) % 8)) + Byte.toUnsignedInt(frameBuffer.get(8)) / 10.0f)));
		} else {
			heatIndex = dewpointSign * (256.0f * (Byte.toUnsignedInt((byte) (frameBuffer.get(9) % 8))) + Byte.toUnsignedInt((byte) (frameBuffer.get(8) / 10.0f)));
		}

		boolean heatValid = (Byte.toUnsignedInt((byte) (frameBuffer.get(9) & 0x20)) == 0);
		if (heatValid) {
			decoded.put("HeatIndex", heatIndex);
		}
		// TODO From documentation online this might also be windchill, investigate
		// adding that to this method
	}

	private void decodeRainfall(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "Rainfall");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Rainfall)) {
			highNibbleDecodeBattery(decoded, frameBuffer.get(0), "Battery");

			// units are 1/100th of an inch
			wordDecode(decoded, frameBuffer, 2, "RainfallRate");
			wordDecode(decoded, frameBuffer, 4, "RainfallHourly");
			wordDecode(decoded, frameBuffer, 6, "RainfallDaily");
			wordDecode(decoded, frameBuffer, 8, "RainfallSinceReset");
			if (useMetric) {
				// TODO add code to convert 1/100 of an inch into mm for these values
				throw (new UnsupportedOperationException("Not yet implemented, sorry!"));
			}

			dataDecodeClock(decoded, frameBuffer, 10, false);
		}
	}

	private void wordDecode(JSONObject decoded, List<Byte> frameBuffer, int i, String key) {
		double word = 256.0 * Byte.toUnsignedInt(frameBuffer.get(i + 1)) + Byte.toUnsignedInt(frameBuffer.get(i));
		decoded.put(key, word);
	}

	private void decodeAnemometer(JSONObject decoded, ArrayList<Byte> frameBuffer) {
		decoded.put("Type", "Anemometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Anemometer)) {
			highNibbleDecodeBattery(decoded, frameBuffer.get(0), "Battery");

			int windDirection = frameBuffer.get(2) % 16;
			decoded.put("WindVectorDegrees", windDirection * 360 / 16);
			decoded.put("WindVectorDescription", DIRECTION_DESCRIPTION[windDirection]);

			float windGust = (256.0f * (Byte.toUnsignedInt(frameBuffer.get(5)) % 16) + Byte.toUnsignedInt(frameBuffer.get(4))) / 10.0f;
			decoded.put("WindGust", String.format("%.1f", windGust));
			float windAverage = (16.0f * Byte.toUnsignedInt(frameBuffer.get(6)) + Byte.toUnsignedInt(frameBuffer.get(5)) / 16) / 10.0f;
			decoded.put("WindAverage", String.format("%.1f", windAverage));

			int chillSign = Byte.toUnsignedInt(frameBuffer.get(8)) / 16; // get wind chill sign nibble
			boolean doWeHaveWindchill = (chillSign & 0x2) == 0;// get wind chill flag
			if (doWeHaveWindchill) {
				chillSign = ((chillSign / 8) == 0) ? +1 : -1; // Nibble determines if windchill is positive or negative.
				float windChill = chillSign * Byte.toUnsignedInt(frameBuffer.get(7));
				decoded.put("WindChill", String.format("%.1f", windChill));
			}
		}
	}

	private void prependDataToStationBuffer(List<Byte> data) {
		int offset = 0;
		for (Byte b : data) {
			stationBuffer.add(offset++,b);
		}
	}
	
	private void appendDataToStationBuffer(int bytes, byte[] buffer) throws IOException {

		int count = buffer[0]; // First byte is the number of expected bytes in this packet.

		if (bytes > 0 & count > 0) {
			if (count < buffer.length) {
				// go through remaining response bytes and copy USB to station buffer (starting
				// at 1, the byte after the length)
				for (int i = 1; i < count + 1; i++) {
					stationBuffer.add(buffer[i + 1]);
				}
			}
		} else {
			log.error("Length of packet : " + count + ", longer than buffer length " + buffer.length);
		}
	}

	private void parseStationData() throws IOException {
		int startDelimiter = getFrameDelimiterPosition(0);

		if (startDelimiter != -1) {
			startDelimiter += 2;
			int finishDelimiter = getFrameDelimiterPosition(startDelimiter);
			if (finishDelimiter != -1) {

				if (startDelimiter < finishDelimiter) {

					// Separate out the current frame, and all data through it's end from
					// stationBuffer
					ArrayList<Byte> frameBuffer = (ArrayList<Byte>) stationBuffer.subList(startDelimiter, finishDelimiter);
					stationBuffer.subList(0, finishDelimiter).clear();

					analyseSensorDataFrame(frameBuffer);
				} else
					log.error("Empty frame received");
			}
		}
	}

	/**
	 * Return start position of frame delimiter (i.e. two byes of 0xFF).
	 * 
	 * @return start position of frame delimiter (-1 if not found)
	 */
	private int getFrameDelimiterPosition(int pos) {
		for (int i = pos; i < stationBuffer.size() - 2; i++) {
			// Look for 2 delimiters in a row, when we find them, return the position
			if (stationBuffer.get(i) == FRAME_BYTE_DELIMITER && stationBuffer.get(i + 1) == FRAME_BYTE_DELIMITER) {
				return (i);
			}
		}
		// Didn't find any
		return (-1);
	}

	public float convertFahrenheitToCelsius(float fahrenheit) {
		return (0.5555f * (fahrenheit - 32.0f));
	}

	private void stationCloseNoThrow() {
		if (hidDevice != null) {
			try {
				hidDevice.close();
			} catch (IOException e) {
				log.error("Error closing hidDevice", e);
			}
		}
	}

	private HIDDevice stationOpen() throws IOException {
		return (hidManager.openById(STATION_VENDOR, STATION_PRODUCT, null));
	}

	private boolean verifyChecksumAndLength(JSONObject decoded, ArrayList<Byte> frameBuffer, DeviceParameters dev) {
		String error = "";
		if (frameBuffer.size() != dev.len) {
			if (frameBuffer.size() > dev.len) {
				log.error("Truncating oversized frame.  Was : "+frameBuffer.size()+ " expected " + dev.len);
				// Our frame is too big, try truncating the frame and see if it processes

				// Return extra data to the buffer
				prependDataToStationBuffer(frameBuffer.subList(dev.len, frameBuffer.size()));

				// Truncate to expected length
				frameBuffer.subList(dev.len, frameBuffer.size()).clear();

				decoded.put("Warn", "Truncated oversized frame");
			} else {
				error += "Frame length incorrect " + frameBuffer.size() + " ";
			}
		}

		if (error.isEmpty()) {

			int expected = Byte.toUnsignedInt(frameBuffer.get(dev.len - 2)) + Byte.toUnsignedInt(frameBuffer.get(dev.len - 1)) * 256;

			int actual = 0;
			for (int i = 0; i < frameBuffer.size() - 2; i++) {
				actual += Byte.toUnsignedInt(frameBuffer.get(i));
			}

			if (expected == actual) {
				decoded.put("Checksum", "Valid");
			} else {
				error += "Invalid [E:" + String.format("%04X", expected) + ",A:" + String.format("%04X", actual) + "] ";
				decoded.put("FrameDump", dumpFrameInformation(frameBuffer));
			}
		}
		if (!error.isEmpty()) {
			decoded.put("Error", error);
		}
		return error.isEmpty();
	}


	private String dumpFrameInformation(List<Byte> frameBuffer) {
		String frameDump = bytesToString(frameBuffer, false);
		frameDump += "[" + frameBuffer.size() + " bytes]";
		return frameDump;
	}

	private String bytesToString(List<Byte> frameBuffer, boolean hexPrefix) {
		String result = "";
		for (byte b : frameBuffer) {
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

	private void byteDecode(JSONObject decoded, int b, String key) {
		decoded.put(key, b);
	}

	private void bitDecode(JSONObject flags, byte b, int i, String key, String if0, String if1) {
		if ((b & 2 ^ i) != 0) {
			flags.put(key, if1);
		} else {
			flags.put(key, if0);
		}
	}

	/**
	 * Re-initialise the weather station then send a data request.
	 * 
	 * @throws input-output exception
	 */
	private void stationDataRequest() throws IOException {
		log.info("Requested weather station data");// output newline
		hidDevice.write(STATION_INITIALISATION_WMR200);
		hidDevice.write(STATION_REQUEST_WMR200);
	}

	private void highNibbleDecodeBattery(JSONObject decoded, byte b, String key) {
		int nibble = getBits(b, 4, 4);
		decoded.put(key, nibble < 4 ? "OK" : "Low");
	}

	private void lowNibbleDecode(JSONObject decoded, byte b, String key) {
		int nibble = getBits(b, 0, 4);
		decoded.put(key, nibble);
	}

	private void twoBitDecode(JSONObject decoded, byte b, int i, String key, String[] choices) {
		int twoBit = getBits(b, i, 2);
		decoded.put(key, choices[twoBit]);
	}

	public int getBits(byte b, int low, int num) {
		int bits = 0;
		for (int i = (low + num) - 1; i >= low; i--) {
			int t = (b & (1 << i)) / (1 << i);
			bits = (bits << 1) + t;
		}
		return bits;
	}

	private int getSign(int signCode) {
		return (signCode == 0 ? +1 : -1); // return sign code
	}

	public boolean isRunning() {
		return running;
	}

	public void setTimezone(TimeZone tz) {
		c.setTimeZone(tz);
		overrideTimezone = true;
	}

	/*
	 * The timezone coming back from the weather station is not valid for the
	 * WMR88A. It's recommended not to allow it to set the timezone for the dates
	 * reported, but to rely on the default timezone, or if necessary override the
	 * timezone via setTimezone();
	 */
	public void setOverrideTimezone(boolean overrideTimezone) {
		this.overrideTimezone = overrideTimezone;
	}

	public void setUseMetric(boolean useMetric) {
		this.useMetric = useMetric;
	}

	public void setCallback(WMR88Callback callback) {
		this.callback = callback;
	}

	public void setReturnInvalidFrames(boolean returnInvalidFrames) {
		this.returnInvalidFrames = returnInvalidFrames;
	}

	public JSONObject analyseSensorDataFrameForTests(byte[] frame) throws IOException {
		ArrayList<Byte> frameBuffer = new ArrayList<Byte>();
		for (byte b : frame) {
			frameBuffer.add(b);
		}
		return analyseSensorDataFrame(frameBuffer);
	}
}
