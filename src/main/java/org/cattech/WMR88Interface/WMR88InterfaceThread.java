package org.cattech.WMR88Interface;

import java.io.IOException;
import java.util.Arrays;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.json.JSONObject;

import com.codeminders.hidapi.HIDDevice;
import com.codeminders.hidapi.HIDManager;

public class WMR88InterfaceThread extends LegacyCode implements Runnable {
	Logger log = LogManager.getLogger(WMR88InterfaceThread.class);


	final String[] DIRECTION_DESCRIPTION = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
	final String[] TREND_DESCRIPTION = { "Steady", "Rising", "Falling" };
	final String[] UV_DESCRIPTION = { "Low", "Medium", "High", "Very High", "Extremely High" };
	private final String[] WEATHER_DESCRIPTION = { "Partly Cloudy", "Rainy", "Cloudy", "Sunny", "?", "Snowy" };

	final int CENTURY = 2000;

	private final byte[] STATION_INITIALISATION_WMR200 = { (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
	private final byte[] STATION_REQUEST_WMR200 = { (byte) 0x00, (byte) 0x01, (byte) 0xD0, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

	private final int FRAME_BYTE_DELIMITER = (byte) 0xFF;

	private final byte CODE_ANEMOMETER = (byte) 0x48;
	private final byte CODE_BAROMETER = (byte) 0x46;
	private final byte CODE_CLOCK = (byte) 0x60;
	private final byte CODE_RAINFALL = (byte) 0x41;
	private final byte CODE_THERMOHYGROMETER = (byte) 0x42;
	private final byte CODE_UV = (byte) 0x47;
	private final int BUFFER_USB_RESP0NSE_BYTES = 40;

	private final int RESPONSE_TIMEOUT_SEC = 10;

	//	Time from last sensor data or data request before requesting data again
	//	(sec); this should be more than 60 seconds (the normal response interval of a WMR100)
	private final int STATION_TIMEOUT_BEFORE_REREQUEST_SEC = 90;

	// ----------------------------- USB variables ------------------------------

	/** Weather station USB vendor/product identifiers */
	private final int STATION_PRODUCT = 0xCA01;
	private final int STATION_VENDOR = 0x0FDE;

	private HIDManager hidManager;
	private HIDDevice hidDevice;

	private long lastDataReceivedMS;

	private int responseByteCount;
	private byte[] responseBufferUSB = new byte[BUFFER_USB_RESP0NSE_BYTES];

	private final int STATION_BUFFER_BYTES = 100;
	private int stationBufferIndexNext;
	private byte[] stationBuffer = new byte[STATION_BUFFER_BYTES];

	private boolean running;

	void calculateWindChill(float windGust, boolean chillValid) {
		//TODO Store previous values to calculate this

//		float windChill;
//		if (outdoorTemperature != DUMMY_VALUE) { // outdoor temperature known?
//			if (windGust > 1.3) { // wind speed high enough?
//				float windPower = // get (gust speed)^0.16
//						(float) Math.pow(windGust, 0.16);
//				windChill = // calculate wind chill (deg C)
//						13.12f + (0.6215f * outdoorTemperature) - (13.956f * windPower) + (0.487f * outdoorTemperature * windPower);
//				if (windChill > outdoorTemperature) // invalid chill
//													// calculation?
//					windChill = outdoorTemperature; // reset chill to outdoor
//													// temp.
//			} else
//				// wind speed not high enough
//				windChill = outdoorTemperature; // set chill to outdoor temp.
//		} else if (!chillValid) // no outdoor temp. or chill?
//			windChill = 0.0f; // set dummy chill of 0 (deg C)
	}

	/**
	 * Analyze and store sensor data according to the weather sensor.
	 * 
	 * @param frame sensor data
	 * @throws input -output exception
	 */
	private void analyseSensorDataFrame(byte[] frame) throws IOException {
		JSONObject decoded = new JSONObject();
		if (validFrame(frame)) {
			long actualTime = currentTime();
			byte sensorCode = frame[1];

			switch (sensorCode) {
			case CODE_ANEMOMETER:
				lastDataReceivedMS = actualTime;
				analyseAnemometer(frame);
				decodeAnemometer(decoded, frame);
				break;
			case CODE_BAROMETER:
				lastDataReceivedMS = actualTime;
				analyseBarometer(frame);
				decodeBarometer(decoded, frame);
				break;
			case CODE_CLOCK:
				analyseClock(frame);
				decodeClock(decoded, frame);
				break;
			case CODE_RAINFALL:
				lastDataReceivedMS = actualTime;
				analyseRainfall(frame);
				decodeRainfall(decoded, frame);
				break;
			case CODE_THERMOHYGROMETER:
				lastDataReceivedMS = actualTime;
				analyseThermohygrometer(frame);
				decodeThermohygrometer(decoded, frame);
				break;
			case CODE_UV:
				lastDataReceivedMS = actualTime;
				analyseUV(frame);
				decodeUV(decoded, frame);
				break;
			default:
				log.error("Ignoring unknown sensor code 0x" + String.format("%02X", sensorCode));
			}
		} else {
			log.error("Ignoring invalid frame " + dumpFrameInformation(frame));
		}
		log.error("Decoded : " + decoded.toString(3));
	}

	private void decodeClock(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Clock");
		if (verifyChecksumAndLength(decoded, frame, 12)) {
			bitDecode(decoded, frame[0], 7, "Powered", "Yes", "No");
			bitDecode(decoded, frame[0], 6, "Battery", "Good", "Low");
			bitDecode(decoded, frame[0], 5, "RFSync", "Inactive", "Active");
			bitDecode(decoded, frame[0], 4, "RFSignal", "Strong", "Weak/Searching"); // TODO Verify this with the display,
			byteDecode(decoded, frame[4], "Minutes");
			byteDecode(decoded, frame[5], "Hour");
			byteDecode(decoded, frame[6], "Day");
			byteDecode(decoded, frame[7], "Month");
			byteDecodeDelta(decoded, frame[8], "Year", +2000);
			byteDecodeSigned(decoded, frame[9], "GMT+-");
		}
	}

	private void decodeBarometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Barometer");
		if (verifyChecksumAndLength(decoded, frame, 8)) {
			decoded.put("pressureAbsolute", 256 * (Byte.toUnsignedInt(frame[3]) % 16) + Byte.toUnsignedInt(frame[2]));
			decoded.put("pressureRelative", 256 * (Byte.toUnsignedInt(frame[5]) % 16) + Byte.toUnsignedInt(frame[4]));
			decoded.put("weatherForcast", getWeather(Byte.toUnsignedInt(frame[3]) / 16));
			decoded.put("weatherPrevious", getWeather(Byte.toUnsignedInt(frame[5]) / 16));
		}
	}

	private void decodeUV(JSONObject decoded, byte[] frame) {
		// TODO Untested, I have no sensor
		decoded.put("Type", "UV");
		if (verifyChecksumAndLength(decoded, frame, 6)) {
			highNibbleDecodePercent(decoded,frame[0],"Battery");
			byteDecode(decoded, 3, "UV_Index");
		}
	}

	private void highNibbleDecodePercent(JSONObject decoded, byte b, String key) {
		int nibble = b >> 4;
		decoded.put(key,(nibble/2^4 *100)+"%");
	}

	private void decodeThermohygrometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Thermohygrometer");
		if (verifyChecksumAndLength(decoded, frame, 12)) {
		}
		// TODO Auto-generated method stub
	}

	private void decodeRainfall(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Rainfall");
		if (verifyChecksumAndLength(decoded, frame, 17)) {
		}
		// TODO Auto-generated method stub
	}

	private void decodeAnemometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Anemometer");
		if (verifyChecksumAndLength(decoded, frame, 11)) {
		}
		// TODO Auto-generated method stub
	}

	private String dumpFrameInformation(byte[] frame) {
		String frameDump = "";

		for (byte b : frame) {
			frameDump += String.format("%02X ", b);
		}

		frameDump += "[" + frame.length + " bytes]";

		return frameDump;
	}

	/**
	 * Analyse USB response for frames, processing then removing these.
	 * 
	 * @param bytes  number of bytes to log
	 * @param buffer data buffer to log
	 * @throws input -output exception
	 */
	private void accumulateAndParseStationData(int bytes, byte[] buffer) throws IOException {
		int i, j; // buffer positions

		int count = buffer[0]; // First byte is the number of expected bytes in this packet.

		if (bytes > 0 & count > 0) {
//			log.debug("USB : " + dumpFrameInformation(Arrays.copyOfRange(buffer, 0, bytes)));

			if (count < buffer.length && stationBufferIndexNext + count <= stationBuffer.length) {
				for (i = 0; i < count; i++) {
					// go through response bytes, copy USB to station buffer
					stationBuffer[stationBufferIndexNext++] = buffer[i + 1];
				}
				int startDelimiter = getDelimiter(0); // check for frame start

				if (startDelimiter != -1) {
					startDelimiter += 2;
					int finishDelimiter = getDelimiter(startDelimiter);
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
	 * Return current time in msec
	 * 
	 * @return current time in msec
	 */
	private long currentTime() {
		return (System.nanoTime() / 1000000);
	}

	/**
	 * Return start position of frame delimiter (i.e. two byes of 0xFF).
	 * 
	 * @return start position of frame delimiter (-1 if not found)
	 */
	private int getDelimiter(int pos) {
		for (int i = pos; i < stationBufferIndexNext - 2; i++) {
			if (stationBuffer[i] == FRAME_BYTE_DELIMITER && stationBuffer[i + 1] == FRAME_BYTE_DELIMITER) {
				return (i);
			}
		}
		return (-1);
	}

	float fahrenheitCelsius(float fahrenheit) {
		return (0.5555f * (fahrenheit - 32.0f));
	}

	String getUV(int uvCode) {
		int uvIndex = // get UV description index
				uvCode >= 11 ? 4 : uvCode >= 8 ? 3 : uvCode >= 6 ? 2 : uvCode >= 3 ? 1 : 0;
		return (UV_DESCRIPTION[uvIndex]); // return UV description
	}

	/**
	 * Return description corresponding to weather code.
	 * 
	 * @param weatherCode weather code
	 * @return weather description
	 */
	String getWeather(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION.length ? WEATHER_DESCRIPTION[weatherCode] : "Unknown");
	}

	/**
	 * Initialise program variables.
	 */
	private void initialise() {
		super.initialise_Old();
		
		log.info("Initializing sensor data");



		lastDataReceivedMS = 0;
		stationBufferIndexNext = 0;
	}

	/**
	 * Close USB connection to weather station.
	 * 
	 * @throws input -output exception
	 */
	private void stationCloseNoThrow() {
		if (hidDevice != null) {
			try {
				hidDevice.close();
			} catch (IOException e) {
				log.error("Error closing hidDevice", e);
			}
		}
	}

	/**
	 * Open USB connection to weather station.
	 * 
	 * @return handle on weather station device
	 * @throws input -output exception
	 */
	private HIDDevice stationOpen() throws IOException {
		return (hidManager.openById(STATION_VENDOR, STATION_PRODUCT, null));
	}

	/**
	 * Repeatedly request data from the weather station, analysing the responses.
	 * Periodically repeat the data request.
	 * 
	 * @throws input-output exception
	 */
	private void stationRead() throws IOException {
		while (true) {
			long actualTime = currentTime();
			if (actualTime - lastDataReceivedMS > STATION_TIMEOUT_BEFORE_REREQUEST_SEC * 1000) {
				stationDataRequest();
				lastDataReceivedMS = actualTime;
			}
			responseByteCount = hidDevice.readTimeout(responseBufferUSB, RESPONSE_TIMEOUT_SEC * 1000);
			accumulateAndParseStationData(responseByteCount, responseBufferUSB);
		}
	}

	private boolean verifyChecksumAndLength(JSONObject decoded, byte[] frame, int expectedLength) {
		boolean valid = true;
		if (frame.length != expectedLength) {
			decoded.put("Error", "Frame length incorrect " + frame.length);
			valid = false;
		}

		if (valid) {

			int expected = Byte.toUnsignedInt(frame[expectedLength-2]) + Byte.toUnsignedInt(frame[expectedLength -1]) * 256;

			int actual = 0;
			for (int i = 0; i < frame.length - 2; i++) {
				actual += Byte.toUnsignedInt(frame[i]);
			}

			if (expected == actual) {
				decoded.put("Checksum", "Valid");
			} else {
				decoded.put("Checksum", "Invalid [E:" + String.format("%04X", expected) + ",A:" + String.format("%04X", actual) + "]");
				decoded.put("FrameDump", dumpFrameInformation(frame));
				valid = false;
			}
		}
		return valid;
	}

	private void byteDecodeSigned(JSONObject decoded, byte b, String key) {
		decoded.put(key, b);
	}

	private void byteDecode(JSONObject decoded, int b, String key) {
		decoded.put(key, b);
	}

	private void byteDecodeDelta(JSONObject decoded, int b, String key, int delta) {
		decoded.put(key, b + delta);
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

	/**
	 * Validate checksum and frame length.
	 * 
	 * @param frame sensor data
	 * @return true if checksum is valid
	 */
	private boolean validFrame(byte[] frame) {
		int length = frame.length;

		int frameCheckSum = 0;
		boolean validFrame = false;

		if (length >= 2) { // at least a two-byte frame?
			for (int i = 0; i < length - 2; i++) {
				frameCheckSum += Byte.toUnsignedInt(frame[i]);
			}

			int expectedCheckSum = 256 * Byte.toUnsignedInt(frame[length - 1]) + Byte.toUnsignedInt(frame[length - 2]);
//			log.debug("Expected checksum : " + expectedCheckSum + " Actual : " + frameCheckSum);

			validFrame = (expectedCheckSum == frameCheckSum);

			if (validFrame) {
				Byte sensorCode = frame[1];

				Integer sensorLength = responseLength.get(sensorCode);

				validFrame = (sensorLength != null && sensorLength.intValue() == length);
			}
		} else {
			log.info("Received short frame, ignoring");
		}

		return (validFrame); // return validity check
	}

	/**
	 * Main thread to open the weather station device, repeatedly read from it, and
	 * route data as required.
	 **/
	@Override
	public void run() {
		Configurator.setRootLevel(Level.DEBUG);

		this.running = true;

		initialise();

		try {
			com.codeminders.hidapi.ClassPathLibraryLoader.loadNativeHIDLibrary();
			hidManager = HIDManager.getInstance();
			hidDevice = stationOpen();

			if (hidDevice == null) {
				throw (new Exception("could not open weather station device"));
			} else {
				stationRead();
			}

		} catch (Throwable throwable) {
			log.error("Thread error: " + throwable);
			throwable.printStackTrace();

			this.running = false;
		} finally {
			stationCloseNoThrow();
		}
	}

	public boolean isRunning() {
		return running;
	}
}
