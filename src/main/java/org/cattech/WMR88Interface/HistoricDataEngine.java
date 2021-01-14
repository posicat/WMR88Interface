package org.cattech.WMR88Interface;

public class HistoricDataEngine {
	
	/**
	 *  TODO Add this library so it can be enabled to store historical data and calculate stored-data based information like :
	 *  	wind chill
	 *  	delta pressure
	 *  	high/low for the day
	 *  	
	 *  NOTE: I don't expect to need all of this functionality in this library, so this is left here to store previous routines 
	 *  that might be helpful but wont' be implemented.  Please feel free to build this class out, and submit pull requests to the code.
	 *  - Posi
	 */
	
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

}
