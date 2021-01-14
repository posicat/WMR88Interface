package org.cattech.WMR88Interface;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
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

	private final static byte CODE_ANEMOMETER = (byte) 0x48;
	private final static byte CODE_BAROMETER = (byte) 0x46;
	private final static byte CODE_CLOCK = (byte) 0x60;
	private final static byte CODE_RAINFALL = (byte) 0x41;
	private final static byte CODE_THERMOHYGROMETER = (byte) 0x42;
	private final static byte CODE_UV = (byte) 0x47;

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

	private final int STATION_BUFFER_BYTES = 100;
	private final int BUFFER_USB_RESP0NSE_BYTES = 40;

	private int responseByteCount;
	private byte[] responseBufferUSB = new byte[BUFFER_USB_RESP0NSE_BYTES];

	private int stationBufferIndexNext;
	private byte[] stationBuffer = new byte[STATION_BUFFER_BYTES];

	Calendar c = Calendar.getInstance();
	
	private boolean running;
	private WMR88Callback callback;

	private boolean returnInvalidFrames = false;
	private boolean useMetric = false;
	private boolean overrideTimezone = true;
	//TODO Add option to include units on all values

	/**
	 * Main thread to open the weather station device, repeatedly read from it, and
	 * route data as required.
	 **/
	@Override
	public void run() {
		this.running = true;

		log.info("Initializing sensor data");

		lastDataReceivedMS = 0;
		stationBufferIndexNext = 0;

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
					responseByteCount = hidDevice.readTimeout(responseBufferUSB, RESPONSE_TIMEOUT_SEC * 1000);
					accumulateAndParseStationData(responseByteCount, responseBufferUSB);
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

	public JSONObject analyseSensorDataFrame(byte[] frame) throws IOException {
		JSONObject decoded = new JSONObject();
		byte sensorCode = frame[1];

		switch (sensorCode) {
		case CODE_ANEMOMETER:
			decodeAnemometer(decoded, frame);
			break;
		case CODE_BAROMETER:
			decodeBarometer(decoded, frame);
			break;
		case CODE_CLOCK:
			decodeClock(decoded, frame);
			break;
		case CODE_RAINFALL:
			decodeRainfall(decoded, frame);
			break;
		case CODE_THERMOHYGROMETER:
			decodeThermohygrometer(decoded, frame);
			break;
		case CODE_UV:
			decodeUV(decoded, frame);
			break;
		default:
			decoded.put("Error", "Received packet for unknown sensor ID : code 0x" + String.format("%02X", sensorCode));
		}

		if (decoded.has("Error")) {
			log.debug("Ignoring invalid frame " + dumpFrameInformation(frame));
			if (returnInvalidFrames) {
				decoded.put("Type", "InvalidFrame");
				decoded.put("Frame", bytesToString(frame, false));
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

//		generateTestCode(frame, decoded); // Convienience method for adding tests quickly.
		return decoded;

	}

	private void generateTestCode(byte[] frame, JSONObject decoded) {
		// Generate test Conditions
		System.out.println("@Test\npublic void testDecode_() throws IOException {");
		System.out.println("	WMR88InterfaceThread it = new WMR88InterfaceThread();");
		System.out.println("	byte[] frame = {" +

				bytesToString(frame, true) + "};");
		System.out.println("	JSONObject expected = new JSONObject(\"" + decoded.toString().replaceAll("[\"]", "\\\\\"") + "\");");
		System.out.println("	JSONObject actual = it.analyseSensorDataFrame(frame);");
		System.out.println("	JSONAssert.assertEquals(expected,actual,false);");
		System.out.println("}");
	}

	private void decodeClock(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Clock");
		if (verifyChecksumAndLength(decoded, frame, 12)) {
			bitDecode(decoded, frame[0], 7, "Powered", "Yes", "No");
			bitDecode(decoded, frame[0], 6, "Battery", "Good", "Low");
			bitDecode(decoded, frame[0], 5, "RFSync", "Inactive", "Active");
			bitDecode(decoded, frame[0], 4, "RFSignal", "Strong", "Weak/Searching"); // TODO Verify this with the display,
			long clockMillis = dataDecodeClock(decoded, frame, 4,true);
			
			long deltaMillis = System.currentTimeMillis() - clockMillis;
			decoded.put("deltaMilis",deltaMillis);
			log.info("Computer time and Station time differ by "+ deltaMillis + "ms");
		}
	}

	private long dataDecodeClock(JSONObject decoded, byte[] frame, int i,boolean containsTZ) {
		c.set(frame[i + 4]+CENTURY, frame[i + 3]-1, frame[i + 2], frame[i + 1], frame[i],0);
		
		// If we allow the timezone to be overridden by the Station, grab and set it here.
		// Note the WMR88A does not return the right timezone, so by default this is turned off.
		if (containsTZ && ! overrideTimezone) {
			TimeZone tz = TimeZone.getDefault();
			byte offset = frame[9];
			tz.setID("UTC +"+offset);
			tz.setRawOffset(1000*60*60*offset);
			c.setTimeZone(tz); 
		}
		
		decoded.put("DateTime", c.getTime().toString());
		long timeInMillis = c.getTimeInMillis()/1000*1000;
		decoded.put("Timestamp", timeInMillis); // Zero out any milliseconds as the clock doesn't even report seconds.
		
		return timeInMillis;
	}

	private void decodeBarometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Barometer");
		if (verifyChecksumAndLength(decoded, frame, 8)) {
			decoded.put("pressureAbsolute", 256 * (Byte.toUnsignedInt(frame[3]) % 16) + Byte.toUnsignedInt(frame[2]));
			decoded.put("pressureRelative", 256 * (Byte.toUnsignedInt(frame[5]) % 16) + Byte.toUnsignedInt(frame[4]));
			decoded.put("weatherForcast", getWeatherDescription(Byte.toUnsignedInt(frame[3]) / 16));
			decoded.put("weatherPrevious", getWeatherDescription(Byte.toUnsignedInt(frame[5]) / 16));
		}
	}

	private String getWeatherDescription(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION.length ? WEATHER_DESCRIPTION[weatherCode] : "Unknown");
	}

	private void decodeUV(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "UV");
		if (verifyChecksumAndLength(decoded, frame, 6)) {
			highNibbleDecodeBattery(decoded, frame[0], "Battery");
			byteDecode(decoded, frame[3], "UV_Index");
		}
	}

	private void decodeThermohygrometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Thermohygrometer");
		if (verifyChecksumAndLength(decoded, frame, 12)) {

			bitDecode(decoded, frame[0], 6, "Battery", "Low", "OK");
			twoBitDecode(decoded, frame[0], 4, "TemperatureTrend", TREND_DESCRIPTION);
			twoBitDecode(decoded, frame[2], 0, "HumidityTrend", TREND_DESCRIPTION);
			twoBitDecode(decoded, frame[2], 0, "Mood", MOOD_FACES);
			lowNibbleDecode(decoded, frame[2], "SensorNumber");

			int temperatureSign = getSign(Byte.toUnsignedInt(frame[4]) / 16);
			float temperature = temperatureSign * (256.0f * (Byte.toUnsignedInt(frame[4]) % 16) + Byte.toUnsignedInt(frame[3])) / 10.0f;
			decoded.put("Temperature", String.format("%.1f", temperature));

			int dewpointSign = getSign(Byte.toUnsignedInt(frame[7]) / 16);
			float dewpoint = dewpointSign * (256.0f * (Byte.toUnsignedInt(frame[7]) % 16) + Byte.toUnsignedInt(frame[6])) / 10.0f;
			decoded.put("DewPoint", String.format("%.1f", dewpoint));

			byteDecode(decoded, frame[5], "Humidity");

			decodeWindChillHeatIndex(decoded, frame);
		}
	}

	private void decodeWindChillHeatIndex(JSONObject decoded, byte[] frame) {
		int dewpointSign = getSign(Byte.toUnsignedInt(frame[7]) / 16);
		
		float heatIndex = 0;
		if (useMetric) {
			heatIndex = dewpointSign * convertFahrenheitToCelsius((256.0f * (Byte.toUnsignedInt(frame[9]) % 8) + Byte.toUnsignedInt(frame[8])) / 10.0f);
		}else {
			heatIndex = dewpointSign * (256.0f * (Byte.toUnsignedInt(frame[9]) % 8) + Byte.toUnsignedInt(frame[8])) / 10.0f;
		}

		boolean heatValid = (Byte.toUnsignedInt(frame[9]) & 0x20) == 0;
		if (heatValid) {
			decoded.put("HeatIndex", heatIndex);
		}
		// TODO From documentation online this might also be windchill, investigate adding that
	}

	private void decodeRainfall(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Rainfall");
		if (verifyChecksumAndLength(decoded, frame, 17)) {
			highNibbleDecodeBattery(decoded, frame[0], "Battery");

			// units are 1/100th of an inch
			wordDecode(decoded, frame, 2, "RainfallRate");
			wordDecode(decoded, frame, 4, "RainfallHourly");
			wordDecode(decoded, frame, 6, "RainfallDaily");
			wordDecode(decoded, frame, 8, "RainfallSinceReset");
			if (useMetric) {
				// TODO add code to convert 1/100 of an inch into mm for these values
				throw(new UnsupportedOperationException("Not yet implemented, sorry!"));
			}

			dataDecodeClock(decoded, frame, 10,false);
		}
	}

	private void wordDecode(JSONObject decoded, byte[] frame, int i, String key) {
		double word = 256.0 * Byte.toUnsignedInt(frame[i + 1]) + Byte.toUnsignedInt(frame[i]);
		decoded.put(key, word);
	}

	private void decodeAnemometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Anemometer");
		if (verifyChecksumAndLength(decoded, frame, 11)) {
			highNibbleDecodeBattery(decoded, frame[0], "Battery");

			int windDirection = frame[2] % 16;
			decoded.put("WindVectorDegrees", windDirection * 360 / 16);
			decoded.put("WindVectorDescription", DIRECTION_DESCRIPTION[windDirection]);

			float windGust = (256.0f * (Byte.toUnsignedInt(frame[5]) % 16) + Byte.toUnsignedInt(frame[4])) / 10.0f;
			decoded.put("WindGust", String.format("%.1f", windGust));
			float windAverage = (16.0f * Byte.toUnsignedInt(frame[6]) + Byte.toUnsignedInt(frame[5]) / 16) / 10.0f;
			decoded.put("WindAverage", String.format("%.1f", windAverage));

			int chillSign = Byte.toUnsignedInt(frame[8]) / 16; // get wind chill sign nibble
			boolean doWeHaveWindchill = (chillSign & 0x2) == 0;// get wind chill flag
			if (doWeHaveWindchill) {
				chillSign = ((chillSign / 8) == 0) ? +1 : -1; // Nibble determines if windchill is positive or negative.
				float windChill = chillSign * Byte.toUnsignedInt(frame[7]);
				decoded.put("WindChill", String.format("%.1f", windChill));
			}
		}
	}

	private void accumulateAndParseStationData(int bytes, byte[] buffer) throws IOException {
		int i, j; // buffer positions

		int count = buffer[0]; // First byte is the number of expected bytes in this packet.

		if (bytes > 0 & count > 0) {

			if (count < buffer.length && stationBufferIndexNext + count <= stationBuffer.length) {
				for (i = 0; i < count; i++) {
					// go through response bytes, copy USB to station buffer
					stationBuffer[stationBufferIndexNext++] = buffer[i + 1];
				}
				int startDelimiter = getFrameDelimiterPosition(0); // check for frame start

				if (startDelimiter != -1) {
					startDelimiter += 2;
					int finishDelimiter = getFrameDelimiterPosition(startDelimiter);
					if (finishDelimiter != -1) {

						if (startDelimiter < finishDelimiter) {
							byte[] frameBuffer = Arrays.copyOfRange(stationBuffer, startDelimiter, finishDelimiter);
							analyseSensorDataFrame(frameBuffer);

							i = 0;
							for (j = finishDelimiter; j < stationBufferIndexNext; j++) {
								// go through data, copy following data down
								stationBuffer[i++] = stationBuffer[j];
							}
							stationBufferIndexNext = i;
						} else
							log.error("Empty frame received");
					}
				}
			} else
				log.error("Over-long response received - count " + count + ", buffer index " + stationBufferIndexNext); // report error
		}
	}

	/**
	 * Return start position of frame delimiter (i.e. two byes of 0xFF).
	 * 
	 * @return start position of frame delimiter (-1 if not found)
	 */
	private int getFrameDelimiterPosition(int pos) {
		for (int i = pos; i < stationBufferIndexNext - 2; i++) {
			if (stationBuffer[i] == FRAME_BYTE_DELIMITER && stationBuffer[i + 1] == FRAME_BYTE_DELIMITER) {
				return (i);
			}
		}
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

	private boolean verifyChecksumAndLength(JSONObject decoded, byte[] frame, int expectedLength) {
		String error = "";
		if (frame.length != expectedLength) {
			if (frame.length > expectedLength) {
				// Try truncating the frame and see if it processes
				frame =  Arrays.copyOfRange(frame, 0, expectedLength);
				decoded.put("Warn","Truncated oversized frame");
			}else {
				error += "Frame length incorrect " + frame.length + " ";
			}
		}

		if (error.isEmpty()) {

			int expected = Byte.toUnsignedInt(frame[expectedLength - 2]) + Byte.toUnsignedInt(frame[expectedLength - 1]) * 256;

			int actual = 0;
			for (int i = 0; i < frame.length - 2; i++) {
				actual += Byte.toUnsignedInt(frame[i]);
			}

			if (expected == actual) {
				decoded.put("Checksum", "Valid");
			} else {
				error += "Invalid [E:" + String.format("%04X", expected) + ",A:" + String.format("%04X", actual) + "] ";
				decoded.put("FrameDump", dumpFrameInformation(frame));
			}
		}
		if (!error.isEmpty()) {
			decoded.put("Error", error);
		}
		return error.isEmpty();
	}

	private String dumpFrameInformation(byte[] frame) {
		String frameDump = bytesToString(frame, false);
		frameDump += "[" + frame.length + " bytes]";
		return frameDump;
	}

	private String bytesToString(byte[] bs, boolean hexPrefix) {
		String result = "";
		for (byte b : bs) {
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
		responseByteCount = hidDevice.write(STATION_INITIALISATION_WMR200);
		responseByteCount = hidDevice.write(STATION_REQUEST_WMR200);
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
		overrideTimezone=true;
	}

	/*
	 * The timezone coming back from the weather station is not valid for the WMR88A.  It's recommended not to allow it to set the timezone
	 * for the dates reported, but to rely on the default timezone, or if necessary override the timezone via setTimezone();
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
}
