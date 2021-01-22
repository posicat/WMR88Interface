package org.cattech.WMR88Interface;

public enum DeviceParameters {
	Rainfall(			(byte)0x41,17),
	Thermohygrometer(	(byte)0x42,12),
	Barometer(			(byte)0x46,8),
	UV(					(byte)0x47,6),
	Anemometer(			(byte)0x48,11),
	Clock(				(byte)0x60,12), 
	INVALID(			(byte)0,-1),
;
	byte id;
	int len;

	DeviceParameters(byte idCode, int packatLength) {
		this.id = idCode;
		this.len = packatLength;
				
	}

	static DeviceParameters lookup(byte b) {
		for (DeviceParameters devParm : DeviceParameters.values()) {
			if (devParm.id == b) {
				return devParm;
			}
		}
		return INVALID;
	}
}
