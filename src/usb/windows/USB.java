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
 * Represents a Universal Serial Bus (USB), which hosts a set of devices.
 * Such buses are largely autoconfiguring.  Aspects such as power
 * management and detailed device configuration involve policy choices,
 * which may be modified after the OS kernel (or other infrastructure)
 * has first set them up.
 *
 * <p> Note that for consistency with USB itself, bus addresses
 * start at one (not zero).
 *
 * @see Host
 *
 * @author David Brownell
 * @author Wayne Westerman
 * @version $Id: USB.java,v 1.2 2005/01/17 07:21:42 westerma Exp $
 */
final class USB implements Bus
{
    /** @serial the host to which this bus is connected */
    final private Host			host;

    // n.b. busfile is like /proc/bus/usb/NNN (decimal)
    //final transient private File	busfile;
    final transient private int		busnum;

// FIXME:  root can change over suspend/resume cycle ...
    private transient int		root = -1;

    final transient private Vector	listeners;
    final transient private DeviceImpl	devices [] = new DeviceImpl [127];

    final transient private String	windriverNameBusID;

    // package private
    USB (String parent, String file, int num, Vector l, Host h)
    throws IOException
    {
	//busfile = new File (parent, file);
	windriverNameBusID = file;
	busnum = num;
	listeners = l;
	host = h;
	root=1;  //since we're flattening the hierarchy for Windows, each device is its own bus and root hub, with dev address assumed 1
	devices[root] = new DeviceImpl(this,parent+file,1);
	//failure of device creation (finding driver of that name) will cause IOException above
	added (devices[root]); //announce the root device to any listeners
    }

    public String toString ()
    {
	StringBuffer buf = new StringBuffer ("{ Windows Bus: ");
	//buf.append (busfile.toString ());
	if (root >= 0) {
	    buf.append (" root = ");
	    buf.append (root);
	    buf.append (" id = ");
	    buf.append (getBusId ());
	}
	buf.append ("}");
	return buf.toString ();
    }

    /** Returns the USB host for this bus. */
    public Host getHost ()
	{ return host;}

    /** Returns the number assigned to this bus. */
    public int getBusNum ()
	{ return busnum;}

    /** Returns the root hub of the bus, if it is known yet. */
    public Device getRootHub ()
	{ return(root >= 0) ? devices [root] : null;}

    public String getBusId ()
    {
	/*if (root < 0)
	    return null;
	return devices [root].getDeviceDescriptor ().getSerial (0);*/
	return windriverNameBusID;
    }

    /**
     * Returns an object representing the device with the specified
     * address (1 through 127), or null if no such device exists.
     */
    public Device getDevice (int address)
    {
	return devices [address - 1];
    }


    boolean scanBus ()
    throws SecurityException
    {
	boolean retval = false;
	return retval;
    }

    // package private
    void kill ()
    {
	if (Windows.trace)
	    System.err.println ("kill bus" + this);

	// notify any listeners that the bus died, and
	// clear backlinks that we control
	if (listeners.size () > 0) {
	    synchronized (devices) {
		for (int i = 0; i < devices.length; i++) {
		    if (devices [i] == null)
		        continue;
		    removed (devices [i]);
		}
	    }
	}
    }

    private void added (DeviceImpl dev)
    {
	if (Windows.trace)
	    System.err.println ("notify add " + dev);

	// call synch'd on devices
	for (int i = 0; i < listeners.size (); i++) {
	    USBListener	listener;
	    listener = (USBListener) listeners.elementAt (i);
	    try { listener.deviceAdded (dev); }
	    catch (Exception e) {
		if (Windows.debug)
		    e.printStackTrace ();
	    }
	}
    }

    private void removed (DeviceImpl dev)
    {
	if (Windows.trace)
	    System.err.println ("notify remove " + dev);

	// call synch'd on devices
	for (int i = 0; i < listeners.size (); i++) {
	    USBListener   listener;
	    listener = (USBListener) listeners.elementAt (i);
	    try {
		listener.deviceRemoved (dev);
	    } catch (Exception e) {
		if (Windows.debug)
		    e.printStackTrace ();
	    }
	}
	try { dev.close (); }
	catch (IOException e) { /* ignore */
	}
    }

    // package private ... for DeviceImpl.close() only !!
    void removeDev (DeviceImpl dev)
    {
	synchronized (devices) {
	    DeviceImpl	d = devices [dev.getAddress () - 1];
	    int		i;

	    if (d == null || d != dev)
		return;
	    i = d.getAddress () - 1;
	    devices [i] = null;
	    if (root == i) {
		root = -1;
		if (Windows.trace)
		    System.err.println ("bus root hub removed!");
		if (Windows.debug) {
		    for (i = 0; i < 127; i++) {
		        if (devices [i] != null)
		            System.err.println ("? addr " + (i + 1)
		                                + " present with no root ?");
		    }
		}
	    }
	}
    }


    // XXX Want management operations to put the bus (via root
    // hub) through the various states:  reset, suspend, resume,
    // operational.  We can invoke reset/suspend/resume methods
    // on the root hub ports, but that's not the same thing.

    // XXX similarly, PM interactions ... hmmm ...
}
