package org.cattech.WMR88Interface;

import java.io.IOException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LegacyCode {
	private Logger log = LogManager.getLogger(LegacyCode.class);
	/** Radio description (indexed by radio code) */
	final String[] RADIO_DESCRIPTION = { "None", "Searching/Weak", "Average", "Strong" };
	/** Weather description (indexed by weather code) */
	private final String[] WEATHER_DESCRIPTION_OLD = { "Partly Cloudy", "Rainy", "Cloudy", "Sunny", "?", "Snowy" };
	final String[] BATTERY_DESCRIPTION_OLD = { "OK", "Low" };
	private final String[] UV_DESCRIPTION_OLD = { "Low", "Medium", "High", "Very High", "Extremely High" };
	/** USB response length for each sensor code */
	protected HashMap<Byte, Integer> responseLength = new HashMap<Byte, Integer>();

	private final byte CODE_ANEMOMETER_OLD = (byte) 0x48;
	private final byte CODE_BAROMETER_OLD = (byte) 0x46;
	private final byte CODE_CLOCK_OLD = (byte) 0x60;
	private final byte CODE_RAINFALL_OLD = (byte) 0x41;
	private final byte CODE_THERMOHYGROMETER_OLD = (byte) 0x42;
	private final byte CODE_UV_OLD = (byte) 0x47;
	
	final String[] TREND_DESCRIPTION_OLD = { "Steady", "Rising", "Falling" };
	final String[] DIRECTION_DESCRIPTION_OLD = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
	final String[] UV_DESCRIPTION = { "Low", "Medium", "High", "Very High", "Extremely High" };

	
	/**
	 * Analyse clock data, but do not store this as it can be unreliable due to
	 * irregular arrival or the checksum not detecting an error.
	 * 
	 * @param frame
	 *            sensor data
	 * @throws input
	 *             -output exception
	 */
	protected void analyseClock(byte[] frame) throws IOException {
		int minute = Byte.toUnsignedInt(frame[4]) % 60; // get minute, limited to 59
		int hour = Byte.toUnsignedInt(frame[5]) % 24; // get hour, limited to 23
		int day = Byte.toUnsignedInt(frame[6]) % 32; // get day, limited to 31
		int month = Byte.toUnsignedInt(frame[7]) % 13; // get month, limited to 12
		int year = 2000 + (Byte.toUnsignedInt(frame[8]) % 100); // get year, limited to
														// 99
		int zoneSign = // get time zone sign
		getSign_OLD(Byte.toUnsignedInt(frame[9]) / 128);
		int zone = Byte.toUnsignedInt(frame[9]) % 128; // get time zone
		int radioLevel = (Byte.toUnsignedInt(frame[0]) / 16) % 4; // get radio level
		String radioDescription = getRadio(radioLevel); // get radio description
		String time = getTime(hour, minute); // get current time
		String date = getDate(year, month, day); // get current date

		log.debug( // log sensor data
			"Clock: " + "Time " + time + ", " + "Date " + date + ", " + "UTC "
					+ String.format("%+2d", zoneSign * zone) + "h, " + "Radio "
					+ radioLevel + " (" + radioDescription + ")");
	}

	/**
	 * Return formatted date.
	 * 
	 * @param year  year
	 * @param month month
	 * @param day   day
	 * @return DD/MM/YYYY
	 */
	protected String getDate(int year, int month, int day) {
		return (String.format("%02d/%02d/%04d", day, month, year));
	}

	/**
	 * Return formatted time.
	 * 
	 * @param hour   hour
	 * @param minute minute
	 * @return HH:MM
	 */
	protected String getTime(int hour, int minute) {
		return (String.format("%02d:%02d", hour, minute));
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
	 * Return sign value corresponding to sign code (0 positive, non-0 negative).
	 * 
	 * @param signCode sign code
	 * @return sign (+1 or -1)
	 */
	protected int getSign_OLD(int signCode) {
		return (signCode == 0 ? +1 : -1); // return sign code
	}

	/**
	 * Analyse and store barometer data (absolute pressure in mb).
	 * 
	 * @param frame sensor data
	 */
	protected void analyseBarometer(byte[] frame) {
		int pressureAbsolute = 256 * (Byte.toUnsignedInt(frame[3]) % 16) + Byte.toUnsignedInt(frame[2]);
		int pressureRelative = 256 * (Byte.toUnsignedInt(frame[5]) % 16) + Byte.toUnsignedInt(frame[4]);
		String weatherForecast = getWeatherOld(Byte.toUnsignedInt(frame[3]) / 16);
		String weatherPrevious = getWeatherOld(Byte.toUnsignedInt(frame[5]) / 16);
	
		log.info("Barometer: " + "Pressure (Abs.) " + pressureAbsolute + "mb, " + "Pressure (Rel.) " + pressureRelative + "mb, " + "Forecast " + weatherForecast + ", " + "Previous " + weatherPrevious);
	}
	
	/**
	 * Return description corresponding to weather code.
	 * 
	 * @param weatherCode weather code
	 * @return weather description
	 */
	String getWeatherOld(int weatherCode) {
		return (weatherCode < WEATHER_DESCRIPTION_OLD.length ? WEATHER_DESCRIPTION_OLD[weatherCode] : "Unknown");
	}

	/**
	 * Analyse and store ultraviolet data (UV index from 0 upwards).
	 * 
	 * @param frame sensor data
	 */
	protected void analyseUV(byte[] frame) {
		String batteryDescription = getBatteryOld(Byte.toUnsignedInt(frame[0]) / 64);
		int uvIndex = Byte.toUnsignedInt(frame[3]) & 0xF;
	
		String uvDescription = getUV_OLD(uvIndex);
	
		log.info("UV: " + "Index " + uvIndex + " (" + uvDescription + "), " + "Battery " + batteryDescription);
	}

	/**
	 * Return description corresponding to battery code.
	 * 
	 * @param batteryCode battery code
	 * @return battery description
	 */
	String getBatteryOld(int batteryCode) {
		int batteryIndex = batteryCode == 0 ? 0 : 1;// get battery description index
		return (BATTERY_DESCRIPTION_OLD[batteryIndex]);
	}

	/**
	 * Return description corresponding to UV code.
	 * 
	 * @param uvCode uvCode code
	 * @return uvCode description
	 */
	String getUV_OLD(int uvCode) {
		int uvIndex = // get UV description index
				uvCode >= 11 ? 4 : uvCode >= 8 ? 3 : uvCode >= 6 ? 2 : uvCode >= 3 ? 1 : 0;
		return (UV_DESCRIPTION_OLD[uvIndex]); // return UV description
	}

	/**
	 * /** Analyse and store rainfall data (total rainfall since midnight in mm).
	 * 
	 * @param frame sensor data
	 */
	protected void analyseRainfall(byte[] frame) {
			String batteryDescription = getBatteryOld(Byte.toUnsignedInt(frame[0]) / 64);
	
	//		statusRain = setBatteryStatus(CODE_RAINFALL, batteryDescription);
	
			float rainRate = getRain(256.0f * Byte.toUnsignedInt(frame[3]) + Byte.toUnsignedInt(frame[2]));// get rainfall rate (mm/hr)
			float rainRecent = getRain(256.0f * Byte.toUnsignedInt(frame[5]) + Byte.toUnsignedInt(frame[4]));// get recent (mm)
			float rainDay = getRain(256.0f * Byte.toUnsignedInt(frame[7]) + Byte.toUnsignedInt(frame[6]));// get rainfall for day (mm)
			float rainReset = getRain(256.0f * Byte.toUnsignedInt(frame[9]) + Byte.toUnsignedInt(frame[8])); // get rainfall since reset (mm)
	
			int minute = Byte.toUnsignedInt(frame[10]) % 60;
			int hour = Byte.toUnsignedInt(frame[11]) % 24;
			int day = Byte.toUnsignedInt(frame[12]) % 32;
			int month = Byte.toUnsignedInt(frame[13]) % 13;
			int year = 2000 + (Byte.toUnsignedInt(frame[14]) % 100);
			String resetTime = getTime(hour, minute);
			String resetDate = getDate(year, month, day);
	
			log.info("Rain Gauge: " + "Rate " + rainRate + "mm/h, " + "Recent " + rainRecent + "mm, " + "24 Hour " + rainDay + "mm, " + "mm, " + "From Reset " + rainReset + "mm, " + "Reset " + resetTime + " " + resetDate
					+ ", " + "Battery " + batteryDescription);
		}

	/**
	 * Return rainfall in inches.
	 * 
	 * @param rain rainfall in 100ths of inches
	 * @return rainfall in mm
	 */
	float getRain(float rain) {
		rain = rain / 100.0f * 25.39f; // get rainfall in mm
		rain = Math.round(rain * 10.0f) / 10.0f; // round to one decimal place
		return (rain); // return rainfall in mm
	}

	/**
	 * Analyse and store thermohygrometer data (indoor/outdoor temperature in
	 * Celsius, indoor/outdoor relative humidity in %, indoor/outdoor dewpoint in
	 * Celsius). If the sensor is not the outdoor one designed for recording,
	 * nothing is logged.
	 * 
	 * @param frame sensor data
	 */
	protected void analyseThermohygrometer(byte[] frame) {
		String batteryDescription = getBatteryOld(Byte.toUnsignedInt(frame[0]) / 64);
		int sensor = Byte.toUnsignedInt(frame[2]) % 16;
		int temperatureSign = getSign_OLD(Byte.toUnsignedInt(frame[4]) / 16);
		float temperature = temperatureSign * (256.0f * (Byte.toUnsignedInt(frame[4]) % 16) + Byte.toUnsignedInt(frame[3])) / 10.0f;
		String temperatureTrend = getTrend((Byte.toUnsignedInt(frame[0]) / 16) % 4);
		int humidity = Byte.toUnsignedInt(frame[5]) % 100;
		String humidityTrend = getTrend((Byte.toUnsignedInt(frame[2]) / 16) % 4);
		int dewpointSign = getSign_OLD(Byte.toUnsignedInt(frame[7]) / 16);
		float dewpoint = dewpointSign * (256.0f * (Byte.toUnsignedInt(frame[7]) % 16) + Byte.toUnsignedInt(frame[6])) / 10.0f;
		boolean heatValid = (Byte.toUnsignedInt(frame[9]) & 0x20) == 0;

		float heatIndex = dewpointSign * fahrenheitCelsius_Old((256.0f * (Byte.toUnsignedInt(frame[9]) % 8) + Byte.toUnsignedInt(frame[8])) / 10.0f);
		String heatDescription = heatValid ? heatIndex + "C" : "N/A";
	
		log.info("Thermohygrometer: " + "Sensor " + sensor + ", " + "Temperature " + temperature + "C (" + temperatureTrend + "), " 
				+ "Humidity " + humidity + "% (" + humidityTrend + "), " + "Dewpoint " + dewpoint + "C, " 
				+ "Index " + heatDescription + ", " + "Battery " + batteryDescription);
	
		if (sensor == 0) {
		} else if (sensor >= 0) {
			//TODO If this sensor is an outdoor sensor, store it to calculate windChill
		}
	}

	public void initialise_Old() {
		// Frame lengths for different frame types
		responseLength.put(CODE_ANEMOMETER_OLD, 11);
		responseLength.put(CODE_BAROMETER_OLD, 8);
		responseLength.put(CODE_CLOCK_OLD, 12);
		responseLength.put(CODE_RAINFALL_OLD, 17);
		responseLength.put(CODE_THERMOHYGROMETER_OLD, 12);
		responseLength.put(CODE_UV_OLD, 6);
	}

	/**
	 * Analyse and store anemometer data (wind direction in degrees, wind speed
	 * average in m/s, wind chill temperature in Celsius). If the base unit reports
	 * a zero (i.e. not applicable) value for wind chill, the outside temperature
	 * (if known) is used instead.
	 * 
	 * @param frame sensor data
	 */
	protected void analyseAnemometer(byte[] frame) {
		String batteryDescription = getBatteryOld(Byte.toUnsignedInt(frame[0]) / 64);
		float windGust =  (256.0f * (Byte.toUnsignedInt(frame[5]) % 16) + Byte.toUnsignedInt(frame[4])) / 10.0f;
		float windAverage = (16.0f * Byte.toUnsignedInt(frame[6]) + Byte.toUnsignedInt(frame[5]) / 16) / 10.0f;
		int windDirection = frame[2] % 16; 
		
		String directionDescription = getDirection(windDirection);
		windDirection = Math.round((frame[2] % 16) * 22.5f);
		int chillSign = Byte.toUnsignedInt(frame[8]) / 16; // get wind chill sign quartet
		boolean chillValid = (chillSign & 0x2) == 0;// get wind chill validity
		chillSign = getSign_OLD(chillSign / 8); // get wind chill sign
		float windChill = chillSign * // get wind chill (deg C)
				Byte.toUnsignedInt(frame[7]);
		String chillDescription = chillValid ? Float.toString(windChill) : "N/A";
	
		log.info("Anemometer: " + "Direction " + windDirection + " (" + directionDescription + "), " + "Average " + windAverage + "m/s, " + "Gust " + windGust + "m/s, " + "Chill " + chillDescription + ", " + "Battery " + batteryDescription);
	
		calculateWindChill(windGust, chillValid);
	}

	private void calculateWindChill(float windGust, boolean chillValid) {
		// TODO Make this happen
	}

	/**
	 * Return description corresponding to trend code.
	 * 
	 * @param trendCode trend code
	 * @return trend description
	 */
	String getTrend(int trendCode) {
		return (trendCode < TREND_DESCRIPTION_OLD.length ? TREND_DESCRIPTION_OLD[trendCode] : "Unknown");
	}

	/**
	 * Return Celsius equivalent of Fahrenheit temperature.
	 * 
	 * @param fahrenheit Fahrenheit temperature
	 * @return Celsius temperature
	 */
	float fahrenheitCelsius_Old(float fahrenheit) {
		return (0.5555f * (fahrenheit - 32.0f));
	}

	/**
	 * Return description corresponding to wind direction code.
	 * 
	 * @param directionCode wind direction code
	 * @return wind direction description
	 */
	String getDirection(int directionCode) {
		return (directionCode < DIRECTION_DESCRIPTION_OLD.length ? DIRECTION_DESCRIPTION_OLD[directionCode] : "Unknown");
	}

	/**
	 * Validate checksum and frame length.
	 * 
	 * @param frame sensor data
	 * @return true if checksum is valid
	 */
	protected boolean validFrame(byte[] frame) {
			int length = frame.length;

			initialise_Old();
			
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

	String getUV(int uvCode) {
		int uvIndex = // get UV description index
				uvCode >= 11 ? 4 : uvCode >= 8 ? 3 : uvCode >= 6 ? 2 : uvCode >= 3 ? 1 : 0;
		return (UV_DESCRIPTION[uvIndex]); // return UV description
	}

}
