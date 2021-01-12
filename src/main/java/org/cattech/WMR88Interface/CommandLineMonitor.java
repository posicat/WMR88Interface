package org.cattech.WMR88Interface;

public class CommandLineMonitor {

	public static void main(String[] args) {
		WMR88InterfaceThread wThread = new WMR88InterfaceThread();

		new Thread(wThread, "WMR88 Interface").start();
		
		while(wThread.isRunning()) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	}

}
