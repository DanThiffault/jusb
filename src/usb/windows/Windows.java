/*
 * Java USB Library
 * Copyright (C) 2000 by David Brownell
 * Copyright (C) 2002 by Wayne Westerman
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package usb.windows;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import usb.core.*;


/**
 * Provides access to native USB host object for this process.
 *
 * @see usb.remote.HostProxy
 *
 * @author David Brownell
 * @author Wayne Westerman
 * @version $Id: Windows.java,v 1.3 2005/01/17 07:21:42 westerma Exp $
 */
public final class Windows extends HostFactory {
    // package private
    static final boolean      trace = false;
    static final boolean      debug = false;

    private static Windows.HostImpl self;
	private static Thread  daemon;


    /**
     * Not part of the API; implements reference implementation SPI.
     */
    public Windows () {}

    /**
     * Not part of the API; implements reference implementation SPI.
     */
    public Host createHost () throws IOException { return Windows.getHost ();}
    public Host createHost (USBListener startup_listener) throws IOException { return Windows.getHost (startup_listener);}


    /**
     * Provides access to the singleton USB Host.
     * This creates a "USB Watcher" daemon thread, which
     * keeps USB device and bus connectivity data current.
     *
     * @return the host, or null if USB support isn't available.
     * @exception IOException for file access problems
     * @exception SecurityException when usbdevfs hasn't been set
     *	up to allow this process to read and write all device nodes
     * @exception RuntimeException various runtime exceptions may
     *	be thrown if the USB information provided by the system
     *	doesn't appear to make sense.
     */
    public static Host getHost ()
    throws IOException, SecurityException
    {
        return getHost(null);
    }

    public static Host getHost (USBListener startup_listener)
    throws IOException, SecurityException
    {
        synchronized (Host.class) {
            if (self == null) {
                System.loadLibrary ("jusbFWin");//
                self = new Windows.HostImpl ("",startup_listener);
                //since static native callbacks use 'self', can't start device scan thread until HostImpl is constructed!
                daemon = new Thread (self, "jUSB-HostWatcher");
				daemon.setPriority(daemon.getPriority ()+1);
                daemon.setDaemon (true);
                daemon.start ();
            }
        }
        return self;
    }



    /*****************************************************************

    /**
     * Represents a Windows host associated with one or more
     * Universal Serial Busses (USBs).
     */
    private static final class HostImpl implements Host, Runnable {
        private final transient Hashtable   busses = new Hashtable (3);
        private final transient Vector      listeners = new Vector (3);
        private String directory_prefix;


        HostImpl (String directory_prefix, USBListener startup_listener)
        throws IOException, SecurityException
        // and RuntimeException on any of several errors
        {
            this.directory_prefix = directory_prefix;
            //devfsPath = directory.getAbsolutePath ();
			if(startup_listener != null)
				addUSBListener(startup_listener);

        }

        protected void finalize ()
        {
            haltDeviceScan ();
            daemon.interrupt ();
        }

        public String toString ()
        {
            return "Windows jusb HostImpl";
        }

        /**
         * Returns an array of objects representing the USB busses currently
         * in this system.
         */
        public Bus [] getBusses ()
        {
            synchronized (busses) {
                Bus      retval [] = new Bus [busses.size ()];
                int      i = 0;

                for (Enumeration e = busses.keys (); e.hasMoreElements (); )
                    retval [i++] = (Bus) busses.get (e.nextElement ());
                return retval;
            }
        }

        public usb.core.Device getDevice (String portId)
        throws IOException
        {
            // hack to work with hotplugging and $DEVICE
            // /proc/bus/usb/BBB/DDD names are unstable, evil !!
            if (portId.startsWith("\\\\?")) {
                synchronized (busses) {
                    Enumeration e = busses.keys ();

                    while (e.hasMoreElements ()) {
                        USB bus = (USB) busses.get (e.nextElement ());
                        if (portId.equals (bus.getBusId ()))
                            continue;
                        return bus.getDevice (0);
                    }
                }
            }
            return null;
        }


        /** Adds a callback for USB structure changes */
        public void addUSBListener (USBListener l)
        {
            if (l == null)
                throw new IllegalArgumentException ();
            listeners.addElement (l);
        }

        /** Removes a callback for USB structure changes */
        public void removeUSBListener (USBListener l)
        {
            // value ignored
            listeners.removeElement (l);
        }

        // hubs usually get polled for interrupts every 255ms ...
        static final int POLL_PERIOD_MS = 750; // milli-seconds

		public void run ()
		{
			while (directory_prefix != null) {

				// No matter how we learn that something may have
				// changed, we do the same thing to figure out
				// exactly what changed:  scan usbdevfs
				scanForDevices(matchUSBvendorID,matchUSBproductID);
				//while (scan ())
				//  continue;

				try {
					Thread.sleep (POLL_PERIOD_MS);
				} catch (InterruptedException e) {
					// set dir to null to cause a clean exit
					directory_prefix = null;
				}
			}
		}

		public void haltDeviceScan() {
			//lets the run thread above exit.
			directory_prefix = null;
		}

        protected void rmBus (Object busname)
        {
            USB  bus = (USB) busses.get (busname);

            if (bus != null) {
                if (trace)
                    System.err.println ("rmBus " + bus);

                for (int i = 0; i < listeners.size (); i++) {
                    USBListener listener;
                    listener = (USBListener) listeners.elementAt (i);
                    try {
                        listener.busRemoved (bus);
                    } catch (Exception e) {
                        e.printStackTrace ();
                    }
                }

                busses.remove (busname);
                bus.kill ();
            } else {
                if (trace)
                    System.err.println ("rmBus: could not find named bus to remove " + busname);
            }
        }

        protected void mkBus (String busname, int busnum)
        throws IOException, SecurityException
        {
            USB  bus;

            bus = new USB (directory_prefix, busname, busnum, listeners, self);
            if (trace)
                System.err.println ("mkBus " + bus);

            busses.put (busname, bus);
            for (int i = 0; i < listeners.size (); i++) {
                USBListener listener;
                listener = (USBListener) listeners.elementAt (i);
                try {
                    listener.busAdded (bus);
                } catch (Exception e) {
                    e.printStackTrace ();
                }
            }

            /*  while (bus.scanBus ())
                  continue;*/
        }

    }
    public static void newDeviceAvailable(int vendor_id, int product_id, int usb_port_address, String pdoname) {
        try {
            if (trace)
                System.err.println ("in java newFWDeviceAvailable w/:"+pdoname);
            self.mkBus (pdoname, usb_port_address);
        } catch (Exception ioe) {
			//catch all exceptions before they get back into native code!
            ioe.printStackTrace ();
        }
    }
    public static void existingDeviceRemoved(int vendor_id, int product_id, int usb_port_address, String pdoname) {
        try {
			self.rmBus (pdoname);
        } catch (Exception ioe) {
			//catch all exceptions before they get back into native code!
            ioe.printStackTrace ();
        }
    }

    public static native void scanForDevices(int match_vendor_id, int match_product_id);
    //public static native int startDeviceWatch(boolean start_not_stop);

}

