package org.cattech.WMR88Interface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CommandLineMonitor {
	static Logger log = LogManager.getLogger(CommandLineMonitor.class);
	
	static class InternalCallback implements WMR88Callback {
		@Override
		public void receiveData(String jsonData) {
			receivedData(jsonData);
		}
	}

	public static void main(String[] args) {
		WMR88InterfaceThread wThread = new WMR88InterfaceThread();

		wThread.setCallback(new InternalCallback());
		
		new Thread(wThread, "WMR88 Interface").start();
		
		while(wThread.isRunning()) {
			try {
				Thread.sleep(100000);
			} catch (InterruptedException e) {
				log.error("Main loop interrupted, terminating",e);
			}
		}
		
	}
	
	private static void receivedData(String jsonData) {
		System.out.println(jsonData);
	}
	

}
