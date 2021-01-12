package org.cattech.WMR88Interface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.json.JSONObject;

import com.codeminders.hidapi.HIDDevice;
import com.codeminders.hidapi.HIDManager;

public class WMR88InterfaceThread implements Runnable {

	private static final int INDEX_SENSOR_NUMBER = 99;

	private Logger log = LogManager.getLogger(this.getClass());

	/** Battery description (indexed by battery code) */
	private final String[] BATTERY_DESCRIPTION = { "OK", "Low" };

	/** Current century */
	private final int CENTURY = 2000;

	/** Trend description (indexed by trend code) */
	private final String[] DIRECTION_DESCRIPTION = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };

	/** Dummy value for initialisation */
	private final float DUMMY_VALUE = -999.0f;

	/** Weather station data frame delimiter */
	private final int FRAME_BYTE = (byte) 0xFF;

	/** Human Interface Device native library name */
	private final String HID_LIBRARY = "hidapi";

	/** Console log output has frames and timestamped messages as received */
	private final int LOG_FRAME = (byte) 0x02;

	/** Console log output has raw USB data as received */
	private final int LOG_USB = (byte) 0x01;

	/** Radio description (indexed by radio code) */
	private final String[] RADIO_DESCRIPTION = { "None", "Searching/Weak", "Average", "Strong" };

	/** Seconds past the hour for logging (for a margin of error in timing) */
	private final int SECOND_OFFSET = 2;

	/** Trend description (indexed by trend code) */
	private final String[] TREND_DESCRIPTION = { "Steady", "Rising", "Falling" };

	/** UV description (indexed by UV code) */
	private final String[] UV_DESCRIPTION = { "Low", "Medium", "High", "Very High", "Extremely High" };

	/** Weather description (indexed by weather code) */
	private final String[] WEATHER_DESCRIPTION = { "Partly Cloudy", "Rainy", "Cloudy", "Sunny", "?", "Snowy" };

	// -------------------------- measurement constants --------------------------

	/** Index of indoor dewpoint measurements (Celsius) */
	private final int INDEX_INDOOR_DEWPOINT = 10;

	/** Index of indoor humidity measurements (%) */
	private final int INDEX_INDOOR_HUMIDITY = 7;

	/** Index of indoor pressure measurements (mbar) */
	private final int INDEX_INDOOR_PRESSURE = 5;

	/** Index of indoor temperature measurements (Celsius) */
	private final int INDEX_INDOOR_TEMPERATURE = 6;

	/** Index of outdoor dewpoint measurements (Celsius) */
	private final int INDEX_OUTDOOR_DEWPOINT = 4;

	/** Index of outdoor humidity measurements (%) */
	private final int INDEX_OUTDOOR_HUMIDITY = 3;

	/** Index of outdoor temperature measurements (Celsius) */
	private final int INDEX_OUTDOOR_TEMPERATURE = 2;

	/** Index of UV index measurements (0..) */
	private final int INDEX_UV_INDEX = 11;

	/** Index of rainfall measurements (mm) */
	private final int INDEX_RAIN_TOTAL = 8;

	/** Index of wind chill measurements (Celsius) */
	private final int INDEX_WIND_CHILL = 9;

	/** Index of wind direction measurements (degrees) */
	private final int INDEX_WIND_DIRECTION = 1;

	/** Index of wind speed measurements (metres/sec) */
	private final int INDEX_WIND_SPEED = 0;

	/** Number of measurement types */
	private final int MEASURE_SIZE = 12;

	/** Number of periods per hour */
	private final int PERIOD_SIZE = 60;

	/** Low battery status symbol */
	private final char STATUS_BATTERY_LOW = '!';

	/** Missing data status symbol */
	private final char STATUS_MISSING = '?';

	/** Sensor status OK symbol */
	private final char STATUS_OK = ' ';

	// ------------------------------ USB constants ------------------------------

	/** Anemometer code */
	private final byte CODE_ANEMOMETER = (byte) 0x48;

	/** Barometer code */
	private final byte CODE_BAROMETER = (byte) 0x46;

	/** Clock code */
	private final byte CODE_CLOCK = (byte) 0x60;

	/** Rainfall bucket code */
	private final byte CODE_RAINFALL = (byte) 0x41;

	/** Thermohygrometer code */
	private final byte CODE_THERMOHYGROMETER = (byte) 0x42;

	/** UV code */
	private final byte CODE_UV = (byte) 0x47;

	/** Size of buffer for USB responses (bytes) */
	private final int RESP0NSE_SIZE = 50;

	/** Timeout for reading USB data (sec) */
	private final int RESPONSE_TIMEOUT = 10;

	/** Weather station initialisation command */
	private final byte[] STATION_INITIALISATION_WMR200 = { (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

	/** Weather station data request command */
	private final byte[] STATION_REQUEST_WMR200 = { (byte) 0x00, (byte) 0x01, (byte) 0xD0, (byte) 0x08, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

	/**
	 * Time from last sensor data or data request before requesting data again
	 * (sec); this should be more than 60 seconds (the normal response interval of a
	 * WMR100)
	 */
	private final int STATION_TIMEOUT = 90;

	/** Size of buffer for accumulated USB data (bytes) */
	private final int STATION_SIZE = 100;

	/** Weather station USB vendor/product identifiers */
	private final int STATION_PRODUCT = 0xCA01;
	private final int STATION_VENDOR = 0x0FDE;

	// ----------------------------- clock variables ----------------------------

	/** Clock day (0..31) */
	private int clockDay;

	/** Clock hour (0..23) */
	private int clockHour;

	/** Clock minute (0..59) */
	private int clockMinute;

	/** Clock day (1..12) */
	private int clockMonth;

	/** Clock day (1..12) */
	private int clockYear;

	// ------------------------- measurement variables ---------------------------

	/** Count of measurement values (indexes measurement, period) */
	private int[][] measureCount = new int[MEASURE_SIZE][PERIOD_SIZE];

	/** Print format strings for reporting measurements */
	private String[] measureFormat = new String[MEASURE_SIZE];

	/** Minimum measurement values (indexes measurement, period) */
	private float[][] measureMin = new float[MEASURE_SIZE][PERIOD_SIZE];

	/** Maximum measurement values (indexes measurement, period) */
	private float[][] measureMax = new float[MEASURE_SIZE][PERIOD_SIZE];

	/** Total measurement values (indexes measurement, period) */
	private float[][] measureTotal = new float[MEASURE_SIZE][PERIOD_SIZE];

	/** Rainfall total for midnight */
	private float outdoorTemperature;

	/** Current period index in hour */
	private int period;

	/** Lowest period index (normally 0, different for first hour of running) */
	private int periodLow;

	/** Initial rainfall offset (normally rain since midnight in mm) */
	private float rainInitial;

	/** Anemometer status */
	private char statusAnemometer;

	/** Barometer status */
	private char statusBarometer;

	/** Rain gauge status */
	private char statusRain;

	/** Outdoor thermohygrometer status */
	private char statusThermohygrometer;

	/** UV sensor status */
	private char statusUV;

	// ----------------------------- USB variables ------------------------------

	/** Human Interface Device manager instance */
	private HIDManager hidManager;

	/** Human Interface Device instance */
	private HIDDevice hidDevice;

	/** Last time sensor data was received or data was requested (msec) */
	private long lastTime;

	/** USB response buffer */
	private byte[] responseBuffer = new byte[RESP0NSE_SIZE];

	/** USB response length for each sensor code */
	private HashMap<Byte, Integer> responseLength = new HashMap<Byte, Integer>();

	/** USB response byte count */
	private int responseBytes;

	/** Accumulated USB data buffer */
	private byte[] stationBuffer = new byte[STATION_SIZE];

	/** Next index to be used in accumulated USB data buffer */
	private int stationNext;

	private boolean running;

	private String protocolVersion = "WMR100";

	// ******************************* Main Program ******************************

	// ********************************* Methods *********************************

	/**
	 * Add a measurement (identified by its index) and value for the current period.
	 * Count, minimum, maximum and total are maintained for each measurement.
	 * 
	 * @param measure measure index
	 * @param value   measure value
	 */
	private void addMeasure(int measure, float value) {
		if (0 <= measure && measure < MEASURE_SIZE && 0 <= period && period < PERIOD_SIZE) {
			int count = measureCount[measure][period];
			if (count == 0) {
				measureMin[measure][period] = value;
				measureMax[measure][period] = value;
				measureTotal[measure][period] = value;
			} else {
				if (value < measureMin[measure][period]) {
					measureMin[measure][period] = value;
				}
				if (value > measureMax[measure][period]) {
					measureMax[measure][period] = value;
				}
				measureTotal[measure][period] += value;
			}
			measureCount[measure][period] = count + 1;
		} else
			log.error("Measure " + measure + " and period " + period + " must be in range"); // report error
	}

	/**
	 * Analyse and store anemometer data (wind direction in degrees, wind speed
	 * average in m/s, wind chill temperature in Celsius). If the base unit reports
	 * a zero (i.e. not applicable) value for wind chill, the outside temperature
	 * (if known) is used instead.
	 * 
	 * @param frame sensor data
	 */
	private void analyseAnemometer(byte[] frame) {
		String batteryDescription = // get battery level description
				getBattery(getInt(frame[0]) / 64);
		statusAnemometer = // set anemometer battery status
				setBatteryStatus(CODE_ANEMOMETER, batteryDescription);
		float windGust = // get wind speed gust (m/s)
				(256.0f * (getInt(frame[5]) % 16) + getInt(frame[4])) / 10.0f;
		float windAverage = // get wind speed average (m/s)
				(16.0f * getInt(frame[6]) + getInt(frame[5]) / 16) / 10.0f;
		int windDirection = frame[2] % 16; // get wind direction (16ths)
		String directionDescription = // get wind direction descr.
				getDirection(windDirection);
		windDirection = // get wind direction (deg)
				Math.round((frame[2] % 16) * 22.5f);
		int chillSign = getInt(frame[8]) / 16; // get wind chill sign quartet
		boolean chillValid = (chillSign & 0x2) == 0;// get wind chill validity
		chillSign = getSign(chillSign / 8); // get wind chill sign
		float windChill = chillSign * // get wind chill (deg C)
				getInt(frame[7]);
		String chillDescription = chillValid ? Float.toString(windChill) : "N/A";

		log.info("Anemometer: " + "Direction " + windDirection + " (" + directionDescription + "), " + "Average " + windAverage + "m/s, " + "Gust " + windGust + "m/s, " + "Chill " + chillDescription + ", " + "Battery " + batteryDescription);

		if (outdoorTemperature != DUMMY_VALUE) { // outdoor temperature known?
			if (windGust > 1.3) { // wind speed high enough?
				float windPower = // get (gust speed)^0.16
						(float) Math.pow(windGust, 0.16);
				windChill = // calculate wind chill (deg C)
						13.12f + (0.6215f * outdoorTemperature) - (13.956f * windPower) + (0.487f * outdoorTemperature * windPower);
				if (windChill > outdoorTemperature) // invalid chill
													// calculation?
					windChill = outdoorTemperature; // reset chill to outdoor
													// temp.
			} else
				// wind speed not high enough
				windChill = outdoorTemperature; // set chill to outdoor temp.
		} else if (!chillValid) // no outdoor temp. or chill?
			windChill = 0.0f; // set dummy chill of 0 (deg C)
		addMeasure(INDEX_WIND_DIRECTION, windDirection); // add wind dir
															// measurement
		addMeasure(INDEX_WIND_SPEED, windAverage); // add wind speed measurement
		addMeasure(INDEX_WIND_CHILL, windChill); // add wind chill measurement
	}

	/**
	 * Analyse and store barometer data (absolute pressure in mb).
	 * 
	 * @param frame sensor data
	 */
	private void analyseBarometer(byte[] frame) {
		int pressureAbsolute = 256 * (getInt(frame[3]) % 16) + getInt(frame[2]);
		int pressureRelative = 256 * (getInt(frame[5]) % 16) + getInt(frame[4]);
		String weatherForecast = getWeather(getInt(frame[3]) / 16);
		String weatherPrevious = getWeather(getInt(frame[5]) / 16);

		log.info("Barometer: " + "Pressure (Abs.) " + pressureAbsolute + "mb, " + "Pressure (Rel.) " + pressureRelative + "mb, " + "Forecast " + weatherForecast + ", " + "Previous " + weatherPrevious);
		addMeasure(INDEX_INDOOR_PRESSURE, pressureAbsolute);
	}

	/**
	 * Analyze and store sensor data according to the weather sensor.
	 * 
	 * @param frame sensor data
	 * @throws input -output exception
	 */
	private void analyseFrame(byte[] frame) throws IOException {
		JSONObject decoded = new JSONObject();
		decoded.put("FrameDump", dumpFrameInformation(frame));

		if (validFrame(frame)) {
			long actualTime = currentTime();
			byte sensorCode = frame[1];

			switch (sensorCode) {
			case CODE_ANEMOMETER:
				lastTime = actualTime;
				analyseAnemometer(frame);
				decodeAnemometer(decoded, frame);
				break;
			case CODE_BAROMETER:
				lastTime = actualTime;
				analyseBarometer(frame);
				decodeBarometer(decoded, frame);
				break;
			case CODE_CLOCK:
				decodeClock(decoded, frame);
				break;
			case CODE_RAINFALL:
				lastTime = actualTime;
				analyseRainfall(frame);
				decodeRainfall(decoded, frame);
				break;
			case CODE_THERMOHYGROMETER:
				lastTime = actualTime;
				analyseThermohygrometer(frame);
				decodeThermohygrometer(decoded, frame);
				break;
			case CODE_UV:
				lastTime = actualTime;
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
			bitDecode(decoded, frame[0], 4, "RFSignal", "Strong","Weak/Searching"); // TODO Verify this with the display, 
			byteDecode(decoded, frame[4], "Minutes");
			byteDecode(decoded, frame[5], "Hour");
			byteDecode(decoded, frame[6], "Day");
			byteDecode(decoded, frame[7], "Month");
			byteDecodeDelta(decoded, frame[8], "Year", +2000);
			byteDecodeSigned(decoded, frame[9], "GMT+-");
		}

//		 * Analyze clock data, but do not store this as it can be unreliable due to
//		 * irregular arrival or the checksum not detecting an error.
		int minute = getInt(frame[4]) % 60;
		int hour = getInt(frame[5]) % 24;
		int day = getInt(frame[6]) % 32;
		int month = getInt(frame[7]) % 13;
		int year = CENTURY + (getInt(frame[8]) % 100);

		int zoneSign = getSign(getInt(frame[9]) / 128);
		int zone = getInt(frame[9]) % 128;
		int radioLevel = (getInt(frame[0]) / 16) % 4;
		String radioDescription = getRadio(radioLevel);
		String time = getTime(hour, minute);
		String date = getDate(year, month, day);

		log.info("Clock: " + "Time " + time + ", " + "Date " + date + ", " + "UTC " + String.format("%+2d", zoneSign * zone) + "h, " + "Radio " + radioLevel + " (" + radioDescription + ")");

	}

	private void decodeUV(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "UV");
		if (verifyChecksumAndLength(decoded, frame, 6)) {
		}
		// TODO Auto-generated method stub

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

	private void decodeBarometer(JSONObject decoded, byte[] frame) {
		decoded.put("Type", "Barometer");
		if (verifyChecksumAndLength(decoded, frame, 8)) {
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
	 * /** Analyse and store rainfall data (total rainfall since midnight in mm).
	 * 
	 * @param frame sensor data
	 */
	private void analyseRainfall(byte[] frame) {
		String batteryDescription = getBattery(getInt(frame[0]) / 64);

		statusRain = setBatteryStatus(CODE_RAINFALL, batteryDescription);

		float rainRate = getRain(256.0f * getInt(frame[3]) + getInt(frame[2]));// get rainfall rate (mm/hr)
		float rainRecent = getRain(256.0f * getInt(frame[5]) + getInt(frame[4]));// get recent (mm)
		float rainDay = getRain(256.0f * getInt(frame[7]) + getInt(frame[6]));// get rainfall for day (mm)
		float rainReset = getRain(256.0f * getInt(frame[9]) + getInt(frame[8])); // get rainfall since reset (mm)

		if (rainInitial == DUMMY_VALUE) {
			rainInitial = rainReset; // use rain since last reset
		} else if (rainReset < rainInitial) {
			rainInitial = -rainInitial; // adjust initial rain offset
		}
		float rainMidnight = Math.round(10.0f * (rainReset - rainInitial)) / 10.0f;

		int minute = getInt(frame[10]) % 60;
		int hour = getInt(frame[11]) % 24;
		int day = getInt(frame[12]) % 32;
		int month = getInt(frame[13]) % 13;
		int year = CENTURY + (getInt(frame[14]) % 100);
		String resetTime = getTime(hour, minute);
		String resetDate = getDate(year, month, day);

		log.info("Rain Gauge: " + "Rate " + rainRate + "mm/h, " + "Recent " + rainRecent + "mm, " + "24 Hour " + rainDay + "mm, " + "From Midnight " + rainMidnight + "mm, " + "From Reset " + rainReset + "mm, " + "Reset " + resetTime + " " + resetDate
				+ ", " + "Battery " + batteryDescription);

		addMeasure(INDEX_RAIN_TOTAL, rainMidnight);
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

			if (count < buffer.length && stationNext + count <= stationBuffer.length) {
				for (i = 0; i < count; i++) {
					// go through response bytes, copy USB to station buffer
					stationBuffer[stationNext++] = buffer[i + 1];
				}
				int startDelimiter = getDelimiter(0); // check for frame start

				if (startDelimiter != -1) {
					startDelimiter += 2;
					int finishDelimiter = getDelimiter(startDelimiter);
					if (finishDelimiter != -1) {

						if (startDelimiter < finishDelimiter) {
							byte[] frameBuffer = Arrays.copyOfRange(stationBuffer, startDelimiter, finishDelimiter);
							analyseFrame(frameBuffer);

							i = 0;
							for (j = finishDelimiter; j < stationNext; j++) {
								// go through data, copy following data down
								stationBuffer[i++] = stationBuffer[j];
							}
							stationNext = i;
						} else
							log.error("Empty frame received");
					}
				}
			} else
				log.error("Over-long response received - count " + count + ", buffer index " + stationNext); // report error
		}
	}

	/**
	 * Analyse and store thermohygrometer data (indoor/outdoor temperature in
	 * Celsius, indoor/outdoor relative humidity in %, indoor/outdoor dewpoint in
	 * Celsius). If the sensor is not the outdoor one designed for recording,
	 * nothing is logged.
	 * 
	 * @param frame sensor data
	 */
	private void analyseThermohygrometer(byte[] frame) {
		String batteryDescription = getBattery(getInt(frame[0]) / 64);
		int sensor = getInt(frame[2]) % 16;
		int temperatureSign = getSign(getInt(frame[4]) / 16);
		float temperature = temperatureSign * (256.0f * (getInt(frame[4]) % 16) + getInt(frame[3])) / 10.0f;
		String temperatureTrend = getTrend((getInt(frame[0]) / 16) % 4);
		int humidity = getInt(frame[5]) % 100;
		String humidityTrend = getTrend((getInt(frame[2]) / 16) % 4);
		int dewpointSign = getSign(getInt(frame[7]) / 16);
		float dewpoint = dewpointSign * (256.0f * (getInt(frame[7]) % 16) + getInt(frame[6])) / 10.0f;
		boolean heatValid = (getInt(frame[9]) & 0x20) == 0;
		float heatIndex = dewpointSign * fahrenheitCelsius((256.0f * (getInt(frame[9]) % 8) + getInt(frame[8])) / 10.0f);
		String heatDescription = heatValid ? heatIndex + "C" : "N/A";

		log.info("Thermohygrometer: " + "Sensor " + sensor + ", " + "Temperature " + temperature + "C (" + temperatureTrend + "), " + "Humidity " + humidity + "% (" + humidityTrend + "), " + "Dewpoint " + dewpoint + "C, " + "Index " + heatDescription
				+ ", " + "Battery " + batteryDescription);

		if (sensor == 0) {
			// Build in sensor
			addMeasure(INDEX_INDOOR_TEMPERATURE, temperature);
			addMeasure(INDEX_INDOOR_HUMIDITY, humidity);
			addMeasure(INDEX_INDOOR_DEWPOINT, dewpoint);
		} else if (sensor >= 0) {
			// External sensor
			statusThermohygrometer = setBatteryStatus(CODE_THERMOHYGROMETER, batteryDescription);
			outdoorTemperature = temperature;
			addMeasure(INDEX_OUTDOOR_TEMPERATURE, temperature);
			addMeasure(INDEX_OUTDOOR_HUMIDITY, humidity);
			addMeasure(INDEX_OUTDOOR_DEWPOINT, dewpoint);
			addMeasure(INDEX_SENSOR_NUMBER, sensor);
		}
	}

	/**
	 * Analyse and store ultraviolet data (UV index from 0 upwards).
	 * 
	 * @param frame sensor data
	 */
	private void analyseUV(byte[] frame) {
		String batteryDescription = getBattery(getInt(frame[0]) / 64);
		statusUV = setBatteryStatus(CODE_UV, batteryDescription);
		int uvIndex = getInt(frame[3]) & 0xF;

		String uvDescription = getUV(uvIndex);

		log.info("UV: " + "Index " + uvIndex + " (" + uvDescription + "), " + "Battery " + batteryDescription);
		addMeasure(INDEX_UV_INDEX, uvIndex); // add UV index measurement
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
		for (int i = pos; i < stationNext - 2; i++) {
			if (stationBuffer[i] == FRAME_BYTE && stationBuffer[i + 1] == FRAME_BYTE) {
				return (i);
			}
		}
		return (-1);
	}

	/**
	 * Return Celsius equivalent of Fahrenheit temperature.
	 * 
	 * @param fahrenheit Fahrenheit temperature
	 * @return Celsius temperature
	 */
	private float fahrenheitCelsius(float fahrenheit) {
		return (0.5555f * (fahrenheit - 32.0f));
	}

	/**
	 * Return description corresponding to battery code.
	 * 
	 * @param batteryCode battery code
	 * @return battery description
	 */
	private String getBattery(int batteryCode) {
		int batteryIndex = batteryCode == 0 ? 0 : 1;// get battery description index
		return (BATTERY_DESCRIPTION[batteryIndex]);
	}

	/**
	 * Return formatted date.
	 * 
	 * @param year  year
	 * @param month month
	 * @param day   day
	 * @return DD/MM/YYYY
	 */
	private String getDate(int year, int month, int day) {
		return (String.format("%02d/%02d/%04d", day, month, year));
	}

	/**
	 * Return description corresponding to wind direction code.
	 * 
	 * @param directionCode wind direction code
	 * @return wind direction description
	 */
	private String getDirection(int directionCode) {
		return (directionCode < DIRECTION_DESCRIPTION.length ? DIRECTION_DESCRIPTION[directionCode] : "Unknown");
	}

	/**
	 * Return integer value of byte.
	 * 
	 * @param value byte value
	 * @return integer value
	 */
	private int getInt(byte value) {
		return (value & 0xFF); // return bottom 8 bits
	}

	/**
	 * Return description corresponding to radio code.
	 * 
	 * @param radioCode radio code
	 * @return radio description
	 */
	private String getRadio(int radioCode) {
		return (radioCode < RADIO_DESCRIPTION.length ? RADIO_DESCRIPTION[radioCode] : "Strong");
	}

	/**
	 * Return rainfall in inches.
	 * 
	 * @param rain rainfall in 100ths of inches
	 * @return rainfall in mm
	 */
	private float getRain(float rain) {
		rain = rain / 100.0f * 25.39f; // get rainfall in mm
		rain = Math.round(rain * 10.0f) / 10.0f; // round to one decimal place
		return (rain); // return rainfall in mm
	}

	/**
	 * Return sign value corresponding to sign code (0 positive, non-0 negative).
	 * 
	 * @param signCode sign code
	 * @return sign (+1 or -1)
	 */
	private int getSign(int signCode) {
		return (signCode == 0 ? +1 : -1); // return sign code
	}

	/**
	 * Return formatted time.
	 * 
	 * @param hour   hour
	 * @param minute minute
	 * @return HH:MM
	 */
	private String getTime(int hour, int minute) {
		return (String.format("%02d:%02d", hour, minute));
	}

	/**
	 * Return description corresponding to trend code.
	 * 
	 * @param trendCode trend code
	 * @return trend description
	 */
	private String getTrend(int trendCode) {
		return (trendCode < TREND_DESCRIPTION.length ? TREND_DESCRIPTION[trendCode] : "Unknown");
	}

	/**
	 * Return description corresponding to UV code.
	 * 
	 * @param uvCode uvCode code
	 * @return uvCode description
	 */
	private String getUV(int uvCode) {
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
	private String getWeather(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION.length ? WEATHER_DESCRIPTION[weatherCode] : "Unknown");
	}

	/**
	 * Initialise program variables.
	 */
	private void initialise() {
		log.info("Initializing sensor data");

		Calendar now = setDate();

		long firstDelay = now.getTimeInMillis(); // get current time in msec
		now.roll(Calendar.MINUTE, true); // go to next minute
		now.set(Calendar.SECOND, SECOND_OFFSET); // set offset seconds
		now.set(Calendar.MILLISECOND, 0); // set zero msec
		firstDelay = now.getTimeInMillis() - firstDelay; // get msec to next
															// minute
		if (firstDelay < 0) { // already at next minute?
			firstDelay = 0;
		}

		// Frame lengths for different frame types
		responseLength.put(CODE_ANEMOMETER, 11);
		responseLength.put(CODE_BAROMETER, 8);
		responseLength.put(CODE_CLOCK, 12);
		responseLength.put(CODE_RAINFALL, 17);
		responseLength.put(CODE_THERMOHYGROMETER, 12);
		responseLength.put(CODE_UV, 6);

		measureFormat[INDEX_WIND_SPEED] = "%5.1f";
		measureFormat[INDEX_WIND_DIRECTION] = "%4.0f";
		measureFormat[INDEX_OUTDOOR_TEMPERATURE] = "%6.1f";
		measureFormat[INDEX_OUTDOOR_HUMIDITY] = "%3.0f";
		measureFormat[INDEX_OUTDOOR_DEWPOINT] = "%6.1f";
		measureFormat[INDEX_INDOOR_PRESSURE] = "%7.1f";
		measureFormat[INDEX_INDOOR_TEMPERATURE] = "%6.1f";
		measureFormat[INDEX_INDOOR_HUMIDITY] = "%3.0f";
		measureFormat[INDEX_RAIN_TOTAL] = "%4.0f";
		measureFormat[INDEX_WIND_CHILL] = "%6.1f";
		measureFormat[INDEX_INDOOR_DEWPOINT] = "%6.1f";
		measureFormat[INDEX_UV_INDEX] = "%3.0f";

		lastTime = 0;
		outdoorTemperature = DUMMY_VALUE;
		rainInitial = DUMMY_VALUE;
		stationNext = 0;

		initialiseMeasures();
	}

	/**
	 * Initialise measurement and period variables.
	 */
	private void initialiseMeasures() {
		statusAnemometer = STATUS_OK;
		statusBarometer = STATUS_OK;
		statusRain = STATUS_OK;
		statusThermohygrometer = STATUS_OK;
		statusUV = STATUS_OK;

		if (clockHour == 0) {
			rainInitial = DUMMY_VALUE;
		}

		period = clockMinute;
		periodLow = period;

		for (int p = periodLow; p < PERIOD_SIZE; p++) {
			for (int m = 0; m < MEASURE_SIZE; m++)
				measureCount[m][p] = 0;
		}
	}

	/**
	 * Log, archive and report hourly data, updating current clock time and daate.
	 * 
	 * @param newHour new hour
	 * @throws input -output exception
	 */
	private void logHour(int newHour) throws IOException {
		logMeasures();

		clockMinute = 0;
		clockHour = newHour;

		reportMeasures();

		// Restore full date post-log
		setDate();
	}

	/**
	 * Log to the day file all measurements (in index order) for each period within
	 * the current hour.
	 * 
	 * @throws input -output exception
	 */
	private void logMeasures() throws IOException {
		File logFile = new File(String.format("%04d%02d%02d.DAT", clockYear, clockMonth, clockDay));

		BufferedWriter logWriter = new BufferedWriter(new FileWriter(logFile, true));

		for (int p = periodLow; p < PERIOD_SIZE; p++) {

			String report = String.format("%02d:%02d:00", clockHour, clockMinute);

			for (int m = 0; m < MEASURE_SIZE; m++) {
				String format = measureFormat[m];
				int count = measureCount[m][p];
				float min = 0.0f;
				float max = 0.0f;
				float total = 0.0f;
				float average = 0.0f;

				if (count > 0) {
					min = measureMin[m][p];
					max = measureMax[m][p];
					total = measureTotal[m][p];
					average = total / count;
				}

				if (m != INDEX_RAIN_TOTAL) {
					report += String.format(format + format + format, average, min, max);
				} else {
					report += String.format(format, average);
				}
			}
			logWriter.write(report); // output report line
			logWriter.newLine(); // append newline to log
		}
		logWriter.close(); // close log file
	}

	/**
	 * Report measurements to log if required.
	 */
	private void reportMeasures() {
		String report = String.format("%02d:%02d ", clockHour, clockMinute);

		for (int m = 0; m < MEASURE_SIZE; m++) {
			int count = 0;
			float total = 0.0f;

			for (int p = periodLow; p < PERIOD_SIZE; p++) {
				int actualCount = measureCount[m][p];

				if (actualCount > 0) {
					count += actualCount;
					total += measureTotal[m][p];
				} else {
					setMissingStatus(m);
				}
			}

			float average = 0.0f;
			if (count > 0) {
				average = total / count;
			}

			if (m == INDEX_WIND_SPEED) {
				report += String.format("Wind" + measureFormat[INDEX_WIND_SPEED] + "m/s" + statusAnemometer, average);

			} else if (m == INDEX_WIND_DIRECTION) {
				report += String.format("Dir" + measureFormat[INDEX_WIND_DIRECTION] + ' ', average);

			} else if (m == INDEX_OUTDOOR_TEMPERATURE) {
				report += String.format("Temp" + measureFormat[INDEX_OUTDOOR_TEMPERATURE] + 'C' + statusThermohygrometer, average);

			} else if (m == INDEX_OUTDOOR_HUMIDITY) {
				report += String.format("Hum" + measureFormat[INDEX_OUTDOOR_HUMIDITY] + "%% ", average);

			} else if (m == INDEX_INDOOR_PRESSURE) {
				report += String.format("Press" + "%5.0f" + "mb" + statusBarometer, average);

			} else if (m == INDEX_RAIN_TOTAL) {
				report += String.format("Rain" + measureFormat[INDEX_RAIN_TOTAL] + "mm" + statusRain, average);

			} else if (m == INDEX_UV_INDEX) {
				report += String.format("UV" + measureFormat[INDEX_UV_INDEX] + statusUV, average);

			}
		}
		log.info(report); // output hourly report
	}

	/**
	 * Return battery status symbol corresponding to sensor code and battery state
	 * description. Also set global battery status for sensor.
	 * 
	 * @param sensorCode         sensor code
	 * @param batteryDescription battery description
	 * @return battery status symbol
	 */
	private char setBatteryStatus(byte sensorCode, String batteryDescription) {

		char batteryStatus = batteryDescription.equals("OK") ? STATUS_OK : STATUS_BATTERY_LOW;

		if (batteryStatus == STATUS_BATTERY_LOW) {

			if (sensorCode == CODE_ANEMOMETER) {
				statusAnemometer = batteryStatus;

			} else if (sensorCode == CODE_RAINFALL) {
				statusRain = batteryStatus;

			} else if (sensorCode == CODE_THERMOHYGROMETER) {
				statusThermohygrometer = batteryStatus;

			} else if (sensorCode == CODE_UV) {
				statusUV = batteryStatus;

			}

		}
		return (batteryStatus); // return battery status symbol
	}

	/**
	 * Set global missing data status for sensor corresponding to measurement index.
	 * 
	 * @param measurementIndex measurement index
	 */
	private void setMissingStatus(int measurementIndex) {

		if (measurementIndex == INDEX_WIND_DIRECTION || measurementIndex == INDEX_WIND_SPEED) {
			statusAnemometer = STATUS_MISSING;

		} else if (measurementIndex == INDEX_INDOOR_PRESSURE) {
			statusBarometer = STATUS_MISSING;

		} else if (measurementIndex == INDEX_RAIN_TOTAL) {
			statusRain = STATUS_MISSING;

		} else if (measurementIndex == INDEX_OUTDOOR_HUMIDITY || measurementIndex == INDEX_OUTDOOR_TEMPERATURE) {
			statusThermohygrometer = STATUS_MISSING;

		} else if (measurementIndex == INDEX_UV_INDEX) {
			statusUV = STATUS_MISSING;

		}
	}

	/**
	 * Extract current day, month and year into global clock variables.
	 * 
	 * @return current calendar
	 */
	private Calendar setDate() {
		Calendar now = Calendar.getInstance(); // get current date and time
		clockHour = now.get(Calendar.HOUR_OF_DAY); // get current hour
		clockMinute = now.get(Calendar.MINUTE); // get current minute
		clockDay = now.get(Calendar.DAY_OF_MONTH); // get current day
		clockMonth = now.get(Calendar.MONTH) + 1; // get current month
		clockYear = now.get(Calendar.YEAR); // get current year
		return (now); // return current calendar
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
			if (actualTime - lastTime > STATION_TIMEOUT * 1000) {
				stationDataRequest();
				lastTime = actualTime;
			}
			responseBytes = hidDevice.readTimeout(responseBuffer, RESPONSE_TIMEOUT * 1000);
			accumulateAndParseStationData(responseBytes, responseBuffer);
		}
	}

	private boolean verifyChecksumAndLength(JSONObject decoded, byte[] frame, int expectedLength) {
		boolean valid = true;
		if (frame.length != expectedLength) {
			decoded.put("Error", "Frame length incorrect " + frame.length);
			valid = false;
		}

		if (valid) {

			int expected = frame[expectedLength - 1] * 256 + frame[expectedLength - 2];

			int actual = 0;
			for (int i = 0; i < frame.length-2; i++) {
				actual += Byte.toUnsignedInt(frame[i]);
			}

			if (expected == actual) {
				decoded.put("Checksum", "Valid");
			} else {
				decoded.put("Checksum", "Invalid [E:" + String.format("%04X", expected) + ",A:" + String.format("%04X", actual)+"]");
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
		responseBytes = hidDevice.write(STATION_INITIALISATION_WMR200);
		responseBytes = hidDevice.write(STATION_REQUEST_WMR200);
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
				frameCheckSum += getInt(frame[i]);
			}

			int expectedCheckSum = 256 * getInt(frame[length - 1]) + getInt(frame[length - 2]);
			log.debug("Expected checksum : " + expectedCheckSum + " Actual : " + frameCheckSum);

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
