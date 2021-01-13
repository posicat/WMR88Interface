package org.cattech.WMR88AInterface;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.cattech.WMR88Interface.WMR88InterfaceThread;
import org.json.JSONObject;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class testFrameDecode {
	JSONCompareMode matchMode = JSONCompareMode.NON_EXTENSIBLE;
	
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
		JSONObject expected = new JSONObject("{\"Type\":\"InvalidFrame\",\"Frame\":\"00,00,20,D4,01,FF,00,FF,90,60,03,10,2B,12,0C,01,15,02,64,01\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual, matchMode);
	}
	
	@Test
	public void testDecode_ValidClockFrame1() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {-112,96,3,16,46,18,12,1,21,2,103,1};
		JSONObject expected = new JSONObject("{\"RFSync\":\"Active\",\"Month\":1,\"Type\":\"Clock\",\"RFSignal\":\"Weak/Searching\",\"Year\":2021,\"Checksum\":\"Valid\",\"Hour\":18,\"Battery\":\"Low\",\"GMT+-\":2,\"Powered\":\"No\",\"Day\":12,\"Minutes\":46}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}
	
	@Test
	public void testDecode_ValidClockFrame2() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {-112,96,3,16,52,18,12,1,21,2,109,1};
		JSONObject expected = new JSONObject("{\"RFSync\":\"Active\",\"Month\":1,\"Type\":\"Clock\",\"RFSignal\":\"Weak/Searching\",\"Year\":2021,\"Checksum\":\"Valid\",\"Hour\":18,\"Battery\":\"Low\",\"GMT+-\":2,\"Powered\":\"No\",\"Day\":12,\"Minutes\":52}");
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
		// TODO I have not tested the results vs an actual UV monitor, as I don't have one.

		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x00,0x47,0x01,0x05,0x4D,0x00};
		JSONObject expected = new JSONObject("{\"Type\":\"UV\",\"UV_Index\":5,\"Checksum\":\"Valid\",\"Battery\":\"OK\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,matchMode);
	}

//	21:22:58.834 [WMR88 Interface] INFO  org.cattech.WMR88Interface.LegacyCode - Thermohygrometer: Sensor 0, Temperature 20.8C (Rising), Humidity 28% (Steady), Dewpoint 2.0C, Index N/A, Battery OK

	@Test
	public void testDecode_Sensor0Valid() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x10,0x42,(byte) 0x80,(byte) 0xD0,0x00,0x1C,0x14,0x00,0x00,0x20,(byte) 0xF2,0x01};
		JSONObject expected = new JSONObject("{\"Type\":\"Thermohygrometer\",\"Temperature\":\"20.8\",\"Mood\":\"\",\"SensorNumber\":0,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"Humidity\":28,\"TemperatureTrend\":\"Rising\",\"HumidityTrend\":\"Stable\",\"DewPoint\":\"2.0\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}

	@Test
	public void testDecode_3rdPartyPacket() throws IOException {
		WMR88InterfaceThread it = new WMR88InterfaceThread();
		byte[] frame = {0x10,0x42,(byte) 0xD0,(byte) 0xD1,0x00,0x62,(byte) 0xD2,0x00,0x00,0x20,0x47,0x03};
		JSONObject expected = new JSONObject("{\"Type\":\"Thermohygrometer\",\"Temperature\":\"20.9\",\"Mood\":\"\",\"SensorNumber\":0,\"Checksum\":\"Valid\",\"Battery\":\"OK\",\"Humidity\":98,\"TemperatureTrend\":\"Rising\",\"HumidityTrend\":\"Stable\",\"DewPoint\":\"21.0\"}");
		JSONObject actual = it.analyseSensorDataFrame(frame);
		JSONAssert.assertEquals(expected,actual,false);
	}
	
}
