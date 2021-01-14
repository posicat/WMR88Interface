package org.cattech.WMR88AInterface;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.cattech.WMR88Interface.WMR88InterfaceThread;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class testFrameDecode {
	JSONCompareMode matchMode = JSONCompareMode.NON_EXTENSIBLE;

	@BeforeClass
	public static void setLogging() {
		Configurator.setRootLevel(Level.DEBUG);
	}
	
	@Test
	public void TestGetBits() {
		// Just a quick test to verify we're getting the bits correctly.
		
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		for(int i = 0; i < 8 ; i++) {
			//1111 1111 - each bit
			assertEquals("bit " + i,1,it.getBits((byte)0xFF, i, 1));
			//0000 0000 - each bit
			assertEquals("bit " + i,0,it.getBits((byte)0x00, i, 1));
		}
		for(int i = 0; i < 8 ; i+=2) {
			//1010 1010 - every other bit
			assertEquals("bit " + i,0,it.getBits((byte)0xAA, i, 1));
			//0101 0101 - every other bit
			assertEquals("bit " + i,1,it.getBits((byte)0x55, i, 1));
		}
		//1111 1111 - lengths
		assertEquals("len 1",1,it.getBits((byte)0xFF, 0, 1));
		assertEquals("len 2",3,it.getBits((byte)0xFF, 0, 2));
		assertEquals("len 3",7,it.getBits((byte)0xFF, 0, 3));
		assertEquals("len 4",15,it.getBits((byte)0xFF, 0, 4));
		assertEquals("len 5",31,it.getBits((byte)0xFF, 0, 5));
		assertEquals("len 6",63,it.getBits((byte)0xFF, 0, 6));
		assertEquals("len 7",127,it.getBits((byte)0xFF, 0, 7));
		assertEquals("len 7",255,it.getBits((byte)0xFF, 0, 8));
		//1010 1010 - lengths
		assertEquals("len 1",0,it.getBits((byte)0xAA, 0, 1));
		assertEquals("len 2",2,it.getBits((byte)0xAA, 0, 2));
		assertEquals("len 3",2,it.getBits((byte)0xAA, 0, 3));
		assertEquals("len 4",10,it.getBits((byte)0xAA, 0, 4));
		assertEquals("len 5",10,it.getBits((byte)0xAA, 0, 5));
		assertEquals("len 6",42,it.getBits((byte)0xAA, 0, 6));
		assertEquals("len 7",42,it.getBits((byte)0xAA, 0, 7));
		assertEquals("len 7",170,it.getBits((byte)0xAA, 0, 8));
	}
	
	@Test
	public void testDecode_InvalidFrame_NoReturn() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		it.setReturnInvalidFrames(false);
		byte[] frame = {0,0,32,-44,1,-1,0,-1,-112,96,3,16,43,18,12,1,21,2,100,1};
		JSONObject expected = new JSONObject("{}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual, matchMode);
	}

	@Test
	public void testDecode_InvalidFrame_WithReturn() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		it.setReturnInvalidFrames(true);
		byte[] frame = {0,0,32,-44,1,-1,0,-1,-112,96,3,16,43,18,12,1,21,2,100,1};
		JSONObject expected = new JSONObject("{\"Type\":\"InvalidFrame\",\"Frame\":\"00,00,20,D4,01,FF,00,FF,90,60,03,10,2B,12,0C,01,15,02,64,01\",\"Error\":\"Received packet for unknown sensor ID : code 0x00\"}");JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual, matchMode);
	}
	
	@Test
	public void testDecode_ValidClockFrame1() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {-112,96,3,16,46,18,12,1,21,2,103,1};
		JSONObject expected = new JSONObject("{\"RFSync\":\"Active\",\"Type\":\"Clock\",\"RFSignal\":\"Weak/Searching\",\"Checksum\":\"Valid\",\"Battery\":\"Low\",\"Powered\":\"No\",\"Timestamp\":1610498760000,\"DateTime\":\"Tue Jan 12 18:46:00 CST 2021\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}
	
	@Test
	public void testDecode_ValidClockFrame2() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {-112,96,3,16,52,18,12,1,21,2,109,1};
		JSONObject expected = new JSONObject("{\"RFSync\":\"Active\",\"Type\":\"Clock\",\"RFSignal\":\"Weak/Searching\",\"Checksum\":\"Valid\",\"Battery\":\"Low\",\"Powered\":\"No\",\"Timestamp\":1610499120000,\"DateTime\":\"Tue Jan 12 18:52:00 CST 2021\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}
	
	@Test
	public void testDecode_Barometer1() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0,70,-38,19,-38,3,16,2};
		JSONObject expected = new JSONObject("{\"Type\":\"Barometer\",\"Checksum\":\"Valid\",\"weatherPrevious\":\"Partly Cloudy\",\"pressureAbsolute\":986,\"pressureRelative\":986,\"weatherForcast\":\"Rainy\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}
	
	@Test
	public void testDecode_Barometer2() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0,70,-38,19,-38,3,16,2};
		JSONObject expected = new JSONObject("{\"Type\":\"Barometer\",\"Checksum\":\"Valid\",\"weatherPrevious\":\"Partly Cloudy\",\"pressureAbsolute\":986,\"pressureRelative\":986,\"weatherForcast\":\"Rainy\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}

	@Test
	public void testDecode_UV_From3rdPartyData() throws IOException {
		// Code found online, test-decoding it here
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x00,0x47,0x01,0x05,0x4D,0x00};
		JSONObject expected = new JSONObject("{\"Type\":\"UV\",\"UV_Index\":5,\"Checksum\":\"Valid\",\"Battery\":\"OK\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}

	@Test
	public void testDecode_Sensor0Valid() throws IOException {
		// Thermohygrometer: Sensor 0, Temperature 20.8C (Rising), Humidity 28% (Steady), Dewpoint 2.0C, Index N/A, Battery OK
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x10,0x42,(byte) 0x80,(byte) 0xD0,0x00,0x1C,0x14,0x00,0x00,0x20,(byte) 0xF2,0x01};
		JSONObject expected = new JSONObject("{\"Type\":\"Thermohygrometer\",\"Temperature\":\"20.8\",\"Mood\":\"\",\"SensorNumber\":0,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"Humidity\":28,\"TemperatureTrend\":\"Rising\",\"HumidityTrend\":\"Stable\",\"DewPoint\":\"2.0\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}

	@Test
	public void testDecode_3rdPartyThermohygrometer() throws IOException {
		// Code found online, test-decoding it here
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x10,0x42,(byte) 0xD0,(byte) 0xD1,0x00,0x62,(byte) 0xD2,0x00,0x00,0x20,0x47,0x03};
		JSONObject expected = new JSONObject("{\"Type\":\"Thermohygrometer\",\"Temperature\":\"20.9\",\"Mood\":\"\",\"SensorNumber\":0,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"Humidity\":98,\"TemperatureTrend\":\"Rising\",\"HumidityTrend\":\"Stable\",\"DewPoint\":\"21.0\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}
	
	@Test
	public void testDecode_LongerFrame_TryToFix() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		it.setReturnInvalidFrames(true);
		byte[] frame = {0x00,0x46,(byte) 0xD0,0x13,(byte) 0xD0,0x03,(byte) 0xFC,0x01,(byte) 0xFF,0x00,0x42,(byte) 0x80,(byte) 0xD7,0x00,0x1C,0x1E,0x00,0x00,0x20
				,(byte) 0xF3,0x01,(byte) 0xFF,(byte) 0x90,0x60,0x03,0x10,0x24,0x16,0x0D,0x01,0x15,0x02,0x62,0x01};
		JSONObject expected = new JSONObject("{\"Type\":\"Barometer\",\"Checksum\":\"Valid\",\"weatherPrevious\":\"Partly Cloudy\",\"pressureAbsolute\":976,\"pressureRelative\":976,\"weatherForcast\":\"Rainy\",\"Warn\":\"Truncated oversized frame\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}
	
	@Test
	public void testDecode_3rdPartyAnemometer() throws IOException {
		// Anemometer: Direction 225 (SW), Average 0.0m/s, Gust 3.7m/s, Chill N/A, Battery OK
		// Code found online, testing it's decode here.
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x00,0x48,0x0A,0x0C,0x25,0x00,0x00,0x00,0x20,(byte) 0xA3,0x00};
		JSONObject expected = new JSONObject("{\"Type\":\"Anemometer\",\"WindVectorDegrees\":225,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"WindGust\":\"3.7\",\"WindVectorDescription\":\"SW\",\"WindAverage\":\"0.0\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}

	@Test
	public void testDecode_3rdPartyRainfall() throws IOException {
		// Rain Gauge: Rate 11439.7mm/h, Recent 2.3mm, 24 Hour 0.0mm, From Reset 42.7mm, Reset 12:40 18/06/2011, Battery OK
		// Rain Gauge: Rate 45056 1/100"/hr, Recent 9 1/100", 24 Hour 0.0 1/100", From Reset 168 1/100", Reset 12:40 06/18/2011, Battery OK
		// Code found online, testing it's decode here.
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x00,0x41,0x00,(byte) 0xB0,0x09,0x00,0x00,0x00,(byte) 0xA8,0x00,0x28,0x0C,0x12,0x06,0x0B,(byte) 0xF9,0x01};
		JSONObject expected = new JSONObject("{\"RainfallRate\":45056,\"Type\":\"Rainfall\",\"RainfallHourly\":9,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"RainfallDaily\":0,\"RainfallSinceReset\":168,\"Timestamp\":1308418800000,\"DateTime\":\"Sat Jun 18 12:40:00 CDT 2011\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}
	
	
	
}
