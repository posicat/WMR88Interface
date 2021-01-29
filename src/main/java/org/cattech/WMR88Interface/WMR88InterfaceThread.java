package org.cattech.WMR88Interface;

import java.io.IOException;
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

	private final int BUFFER_USB_RESP0NSE_BYTES = 9;

	private WMRBuffer stationBuffer = new WMRBuffer();

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
					stationBuffer.append(responseByteCount, responseBufferUSB);
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

	public JSONObject analyseSensorDataFrame(WMRBuffer frameBuffer) throws IOException {
		JSONObject decoded = new JSONObject();

		DeviceParameters devParm = DeviceParameters.lookup(frameBuffer.getByte(1));

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
			decoded.put("Error", "Received packet for unknown sensor ID : code 0x" + String.format("%02X", frameBuffer.getByte(1)));
		}

		if (decoded.has("Error")) {
			log.debug("Ignoring invalid frame " + frameBuffer.toString());
			if (returnInvalidFrames) {
				decoded.put("Type", "InvalidFrame");
				decoded.put("Frame", frameBuffer.toString());
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

//		generateTestCode(frameBuffer, decoded); // Convenience method for adding tests quickly.
		return decoded;

	}

	@SuppressWarnings("unused")
	private void generateTestCode(WMRBuffer frameBuffer, JSONObject decoded) {
		// Generate test Conditions
		System.out.println("@Test\npublic void testDecode_() throws IOException {");
		System.out.println("	WMR88InterfaceThread it = new WMR88InterfaceThread();");
		System.out.println("	byte[] frame = " + frameBuffer.toJavaArray());
		System.out.println("	JSONObject expected = new JSONObject(\"" + decoded.toString().replaceAll("[\"]", "\\\\\"") + "\");");
		System.out.println("	JSONObject actual = it.analyseSensorDataFrame(new WMRBuffer(frame));"); 
		System.out.println("	JSONAssert.assertEquals(expected,actual,false);");
		System.out.println("}");
	}

	private void decodeClock(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "Clock");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Clock)) {
			decoded.put("Powered", frameBuffer.getBitAsString(0, 7, "Yes", "No"));
			decoded.put("Battery", frameBuffer.getBitAsString(0, 6, "Good", "Low"));
			decoded.put("RFSync", frameBuffer.getBitAsString(0, 5, "Inactive", "Active"));
			decoded.put("RFSignal", frameBuffer.getBitAsString(0, 4, "Strong", "Weak/Searching")); // TODO Verify this with the display

			long clockMillis = dataDecodeClockField(decoded, frameBuffer, 4, true);

			long deltaMillis = System.currentTimeMillis() - clockMillis;
			decoded.put("deltaMilis", deltaMillis);
			log.info("Computer time and Station time differ by " + deltaMillis + "ms");
		}
	}

	private long dataDecodeClockField(JSONObject decoded, WMRBuffer frameBuffer, int i, boolean containsTZ) {
		c.set(frameBuffer.getByte(i + 4) + CENTURY, frameBuffer.getByte(i + 3) - 1, frameBuffer.getByte(i + 2), frameBuffer.getByte(i + 1), frameBuffer.getByte(i), 0);

		// If we allow the timezone to be overridden by the Station, grab and set it
		// here.
		// Note the WMR88A does not return the right timezone, so by default this is
		// turned off.
		if (containsTZ && !overrideTimezone) {
			TimeZone tz = TimeZone.getDefault();
			int offset = frameBuffer.getByte(9);
			tz.setID("UTC +" + offset);
			tz.setRawOffset(1000 * 60 * 60 * offset);
			c.setTimeZone(tz);
		}

		decoded.put("DateTime", c.getTime().toString());
		long timeInMillis = c.getTimeInMillis() / 1000 * 1000;
		decoded.put("Timestamp", timeInMillis); // Zero out any milliseconds as the clock doesn't even report seconds.

		return timeInMillis;
	}

	private void decodeBarometer(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "Barometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Barometer)) {
			decoded.put("pressureAbsolute", frameBuffer.getNibbles(2,0,3));
			decoded.put("pressureRelative", 256 * (frameBuffer.getByte(5) % 16) + frameBuffer.getByte(4));
			decoded.put("weatherForcast", getWeatherDescription(frameBuffer.getByte(3) / 16));
			decoded.put("weatherPrevious", getWeatherDescription(frameBuffer.getByte(5) / 16));
		}
	}

	private String getWeatherDescription(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION.length ? WEATHER_DESCRIPTION[weatherCode] : "Unknown");
	}

	private void decodeUV(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "UV");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.UV)) {
			decoded.put("Battery", decodeBattery(frameBuffer.getNibble(0, 1)));
			decoded.put("UV_Index", frameBuffer.getByte(3));
		}
	}

	private void decodeThermohygrometer(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "Thermohygrometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Thermohygrometer)) {

			decoded.put("Battery", frameBuffer.getBitAsString(0, 6, "Low", "OK"));
			decoded.put("TemperatureTrend", frameBuffer.getBitsAsString(0, 4, 2, TREND_DESCRIPTION));
			decoded.put("HumidityTrend", frameBuffer.getBitsAsString(2, 0, 2, TREND_DESCRIPTION));
			decoded.put("Mood", frameBuffer.getBitsAsString(2, 6, 2, MOOD_FACES));
			decoded.put("SensorNumber", frameBuffer.getNibble(2, 0));

			int temperatureSign = getSign(frameBuffer.getNibble(4, 1));
			float temperature = temperatureSign * frameBuffer.getNibbles(3,0,3) / 10.0f;
			decoded.put("Temperature", String.format("%.1f", temperature));

			int dewpointSign = getSign(frameBuffer.getNibble(7, 1));
			float dewpoint = dewpointSign * frameBuffer.getNibbles(6,0,3) / 10.0f;
			decoded.put("DewPoint", String.format("%.1f", dewpoint));
			decoded.put("Humidity", frameBuffer.getByte(5));

			decodeWindChillHeatIndex(decoded, frameBuffer);
		}
	}

	private void decodeWindChillHeatIndex(JSONObject decoded, WMRBuffer frameBuffer) {
		int dewpointSign = getSign(frameBuffer.getNibble(7, 1));

		float heatIndex = 0;
		if (useMetric) {
			heatIndex = dewpointSign * convertFahrenheitToCelsius(frameBuffer.getNibbles(8,0,3) / 10.0f);
		} else {
			heatIndex = dewpointSign * frameBuffer.getNibbles(8,0,3) / 10.0f;
		}

		boolean heatValid = (Byte.toUnsignedInt((byte) (frameBuffer.getByte(9) & 0x20)) == 0);
		if (heatValid) {
			decoded.put("HeatIndex", heatIndex);
		}
		// TODO From documentation online this might also be windchill, investigate
		// adding that to this method
	}

	private void decodeRainfall(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "Rainfall");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Rainfall)) {
			decoded.put("Battery", decodeBattery(frameBuffer.getNibble(0, 1)));

			if (useMetric) {
				// Convert 1/10th of an inch to mm 
				decoded.put("RainfallRate",String.format("%.2f",frameBuffer.getWord(2)*0.254f));
				decoded.put("RainfallHourly",String.format("%.2f",frameBuffer.getWord(4)*0.254f));
				decoded.put("RainfallDaily",String.format("%.2f",frameBuffer.getWord(6)*0.254f));
				decoded.put("RainfallSinceReset",String.format("%.2f",frameBuffer.getWord(8)*0.254f));
			} else {
				// units are 1/10th of an inch, convert to inches
				decoded.put("RainfallRate",String.format("%.1f",frameBuffer.getWord(2)/10f));
				decoded.put("RainfallHourly",String.format("%.1f",frameBuffer.getWord(4)/10f));
				decoded.put("RainfallDaily",String.format("%.1f",frameBuffer.getWord(6)/10f));
				decoded.put("RainfallSinceReset",String.format("%.1f",frameBuffer.getWord(8)/10f));
			}

			dataDecodeClockField(decoded, frameBuffer, 10, false);
		}
	}
	
	private void decodeAnemometer(JSONObject decoded, WMRBuffer frameBuffer) {
		decoded.put("Type", "Anemometer");
		if (verifyChecksumAndLength(decoded, frameBuffer, DeviceParameters.Anemometer)) {
			decoded.put("Battery", decodeBattery(frameBuffer.getNibble(0, 1)));

			int windDirection = frameBuffer.getByte(2) % 16;
			decoded.put("WindVectorDegrees", windDirection * 360 / 16);
			decoded.put("WindVectorDescription", DIRECTION_DESCRIPTION[windDirection]);

			decoded.put("WindGust", String.format("%.1f", frameBuffer.getNibbles(4,0,3) / 10.0f));
			
			decoded.put("WindAverage", String.format("%.1f", frameBuffer.getNibbles(5,1,3) / 10.0f));

			int chillSign = frameBuffer.getByte(8) / 16; // get wind chill sign nibble
			boolean doWeHaveWindchill = (chillSign & 0x2) == 0;// get wind chill flag
			if (doWeHaveWindchill) {
				chillSign = ((chillSign / 8) == 0) ? +1 : -1; // Nibble determines if windchill is positive or negative.
				float windChill = chillSign * frameBuffer.getByte(7);
				decoded.put("WindChill", String.format("%.1f", windChill));
			}
		}
	}



	private int getSign(int signCode) {
		return (signCode == 0 ? +1 : -1); // return sign code
	}


	private String decodeBattery(int nibble) {
		return nibble < 4 ? "OK" : "Low";
	}

	public float convertFahrenheitToCelsius(float fahrenheit) {
		return (0.5555f * (fahrenheit - 32.0f));
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
					WMRBuffer frameBuffer = new WMRBuffer(stationBuffer.subList(startDelimiter, finishDelimiter));
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
			if (stationBuffer.getByte(i) == FRAME_BYTE_DELIMITER && stationBuffer.getByte(i + 1) == FRAME_BYTE_DELIMITER) {
				return (i);
			}
		}
		// Didn't find any
		return (-1);
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

	private boolean verifyChecksumAndLength(JSONObject decoded, WMRBuffer frameBuffer, DeviceParameters dev) {
		String error = "";
		if (frameBuffer.size() != dev.len) {
			if (frameBuffer.size() > dev.len) {
				log.error("Truncating oversized frame.  Was : " + frameBuffer.size() + " expected " + dev.len);
				// Our frame is too big, try truncating the frame and see if it processes

				// Return extra data to the buffer
				stationBuffer.prepend(frameBuffer.subList(dev.len, frameBuffer.size()));

				// Truncate to expected length
				frameBuffer.subList(dev.len, frameBuffer.size()).clear();

				decoded.put("Warn", "Truncated oversized frame");
			} else {
				error += "Frame length incorrect " + frameBuffer.size() + " ";
			}
		}

		if (error.isEmpty()) {

			int expected = frameBuffer.getByte(dev.len - 2) + frameBuffer.getByte(dev.len - 1) * 256;

			int actual = 0;
			for (int i = 0; i < frameBuffer.size() - 2; i++) {
				actual += frameBuffer.getByte(i);
			}

			if (expected != actual) {
				error += "Invalid [E:" + String.format("%04X", expected) + ",A:" + String.format("%04X", actual) + "] ";
				decoded.put("FrameDump", frameBuffer.toStringAndLength());
			}
		}
		if (!error.isEmpty()) {
			decoded.put("Error", error);
		}
		return error.isEmpty();
	}

	/**
	 * Re-initialise the weather station then send a data request.
	 * 
	 * @throws input-output exception
	 */
	private void stationDataRequest() throws IOException {
		log.info("Requested weather station data");
		hidDevice.write(STATION_INITIALISATION_WMR200);
		hidDevice.write(STATION_REQUEST_WMR200);
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
}
