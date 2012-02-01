/*
 * Java USB Library
 * Copyright (C) 2000 by David Brownell
 *    getHost(startup_listener) API added by Wayne Westerman in 2002.
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

package usb.linux;

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
 * @version $Id: Linux.java,v 1.5 2005/01/17 07:19:42 westerma Exp $
 */
public final class Linux extends HostFactory
{
    // package private
    static final boolean		trace = false;
    static final boolean		debug = false;

    private static Linux.HostImpl	self;


    /**
     * Not part of the API; implements reference implementation SPI.
     */
    public Linux () { }

    /**
     * Not part of the API; implements reference implementation SPI.
     */
    public Host createHost () throws IOException { return Linux.getHost (); }
    public Host createHost (USBListener startup_listener) throws IOException { return Linux.getHost (startup_listener);}


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

		// no existing host; make our own.
		File f = new File ("/proc/bus/usb");
		if (!f.exists () || !f.isDirectory ()) {
		    System.err.println (
			"Java USB for Linux needs usbdevfs to run."
			);
		    return null;
		}

		// With GCJ we expect to run native, not interpreted,
		// and use CNI glue instead of a separate JNI library.
		if (!"libgcj".equals (System.getProperty ("java.vm.name")))
		    System.loadLibrary ("jusb");

		self = new Linux.HostImpl (f,startup_listener);
	    }
	}
	return self;
    }

    /******************************************************************/

    // FIXME: provide some way to expose devfs info, so we have it
    // as an integration option for other Linux/GNOME/... apps.

    static private String	devfsPath;
    static private Watcher	watcher;
    static private Thread	daemon;

    /**
     * Represents a Linux host associated with one or more
     * Universal Serial Busses (USBs).
     */
    private static final class HostImpl implements Host
    {
	private final transient Hashtable	busses = new Hashtable (3);
	private final transient Vector		listeners = new Vector (3);


	HostImpl (File directory, USBListener startup_listener)
	throws IOException, SecurityException
	// and RuntimeException on any of several errors
	{
	    devfsPath = directory.getAbsolutePath ();
		if(startup_listener != null)
			addUSBListener(startup_listener);

	    watcher = new Watcher (directory, busses, listeners);

	    daemon = new Thread (watcher, "USB-Watcher");
	    daemon.setDaemon (true);
	    daemon.start ();
	}

	protected void finalize ()
	{
	    watcher.halt ();
	    daemon.interrupt ();
	}

	public String toString ()
	{
	    return "Linux usbfs";
	}

	/**
	 * Returns an array of objects representing the USB busses currently
	 * in this system.
	 */
	public Bus [] getBusses ()
	{
	    synchronized (busses) {
		Bus		retval [] = new Bus [busses.size ()];
		int		i = 0;

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
	    if (portId.startsWith (devfsPath)) {
		String	temp;
		int	busNum, devAddr;

	    	temp = portId.substring (devfsPath.length () + 1);
		busNum = Integer.parseInt (temp.substring (0, 3), 10);
		devAddr = Integer.parseInt (temp.substring (4, 7), 10);

		synchronized (busses) {
		    Enumeration e = busses.keys ();

		    while (e.hasMoreElements ()) {
			USB bus = (USB) busses.get (e.nextElement ());
			if (bus.getBusNum () != busNum)
			    continue;
			return bus.getDevice (devAddr);
		    }
		    return null;
		}
	    }
	    return new PortIdentifier (portId).getDevice (this);
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
    }


    // hubs usually get polled for interrupts every 255ms ...
    static final int POLL_PERIOD = 2;	// seconds


    /**
     * Scan for bus additions/removals/changes, delegating
     * most work to the
     */
    private static final class Watcher implements Runnable
    {
	private File			dir;
	private File			devices;
	private final Hashtable		busses;
	private final Vector		listeners;
	private long			lastTime;

	// package private
	Watcher (File d, Hashtable b, Vector l)
	throws IOException, SecurityException
	// throws RuntimeException on any of several errors
	{
	    dir = d;
	    devices = new File (dir, "devices");
	    busses = b;
	    listeners = l;
	    if (!dir.exists () || !dir.isDirectory ())
		throw new IOException (
		      "is usbdevfs mounted?  "
		    + d.getAbsolutePath ());

	    // initial population of this bus
	    while (scan ())
		continue;

	    if (busses.isEmpty ())
		throw new IOException (
		    "no devices; maybe usbdevfs denies read/write access?");
	}

	public void run ()
	{
	    while (dir != null) {

		// No matter how we learn that something may have
		// changed, we do the same thing to figure out
		// exactly what changed:  scan usbdevfs
		while (scan ())
		    continue;

		// FIXME:  add native support to poll() on
		// /proc/bus/usb/devices ...

		try { Thread.sleep (POLL_PERIOD * 1000); }
		catch (InterruptedException e) {
		    // set dir to null to cause a clean exit
		}
	    }
	}

	void halt ()
	{
	    dir = null;
	}

	private boolean scan ()
	throws SecurityException
	{
	    boolean	changed = false;

	    synchronized (busses) {
		long	current = System.currentTimeMillis ();
		long	mtime = devices.lastModified ();

		if (lastTime > mtime) {
		    // works since 2.4.0-test8 or so
		    if (trace)
			System.err.println ("Host.scan: unmodified");
		    return false;
		}
		if (trace)
		    System.err.println ("Host.scan: modified ...");

		// what busses exist now?
		String	kids [] = dir.list ();
		Vector	seen;

		if (kids.length < 2)
		    throw new IllegalArgumentException (
				dir.getAbsolutePath ());

		seen = new Vector (kids.length - 2);
		for (int i = 0; i < kids.length; i++) {
		    int	busnum;
		    try {
			// Bus number:  "001", "002", ...
			busnum = Integer.parseInt (kids [i]);
			seen.addElement (kids [i]);

			USB	bus = (USB) busses.get (kids [i]);

			// new bus?
			if (bus == null) {
			    mkBus (kids [i], busnum);
			    changed = true;

			// new bus, but we missed a removal?

			// FIXME:  we can't tell from here!!

			// the same bus as last time?
			} else {
			    while (bus.scanBus ())
				changed = true;
			}

		    } catch (IOException e) {
			System.err.println ("I/O problem: " + kids [i]);
			e.printStackTrace ();

		    } catch (SecurityException e) {
			throw e;

		    } catch (Exception e) {
			// Special:  "devices", "drivers", ...
			if ("devices".equals (kids [i]))
			    continue;
			if ("drivers".equals (kids [i]))
			    continue;

			// excuuuse me???
			System.err.println ("Not a usbdevfs bus: "
				+ kids [i]);
			e.printStackTrace ();
		    }
		}

		// what busses previously existed ... but don't now?
		for (Enumeration e = busses.keys ();
			e.hasMoreElements ();
			) {
		    Object busname = e.nextElement ();
		    if (!seen.contains (busname)) {
			if (trace)
			    System.err.println ("bus gone: " + busname);
			rmBus (busname);
			changed = true;
		    }
		}

		// we saw all changes before lastTime
		lastTime = current;
	    }

	    // if changed, bus may not have quiesced yet
	    return changed;
	}

	private void rmBus (Object busname)
	{
	    USB	bus = (USB) busses.get (busname);

	    if (trace)
		System.err.println ("rmBus " + bus);

	    for (int i = 0; i < listeners.size (); i++) {
		USBListener	listener;
		listener = (USBListener) listeners.elementAt (i);
		try { listener.busRemoved (bus); }
		catch (Exception e) {
		    e.printStackTrace ();
		}
	    }

	    busses.remove (busname);
	    bus.kill ();
	}

	private void mkBus (String busname, int busnum)
	throws IOException, SecurityException
	{
	    USB	bus;

	    bus = new USB (dir, busname, busnum, listeners, self);
	    if (trace)
		System.err.println ("mkBus " + bus);

	    busses.put (busname, bus);
	    for (int i = 0; i < listeners.size (); i++) {
		USBListener	listener;
		listener = (USBListener) listeners.elementAt (i);
		try { listener.busAdded (bus); }
		catch (Exception e) {
		    e.printStackTrace ();
		}
	    }

	    while (bus.scanBus ())
		continue;
	}
    }
}
