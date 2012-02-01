/*
 * Java USB Library
 * Copyright (C) 2001 by David Brownell
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

package usb.util;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import usb.core.*;


/**
 * This class supports downloading firmware to devices based on Cypress
 * EZ-USB FX2 microcontrollers.  These 8-bit microcontrollers (8051
 * family) can be used in completely "soft" modes, where all firmware
 * is downloaded from the host, as well as with firmware stored on ROM.
 * After downloading firmware, the device
 * normally performs "ReNumeration" (tm), reinitializing with new device
 * descriptors to reflect functionality provided by the firmware.
 * (Sometimes downloading more firmware, into external memory banks.)
 *
 * <hr/>
 *
 * <p> Various Java Virtual Machines exist for 8051 processors.
 * You may first have heard of embedded Java with "Java Rings",
 * which used such 8051 processors.  Commercial versions, even
 * using bank switching to enlarge the 16 bit address space,
 * are now available from several sources.
 *
 * <p> Most developers use C and assembly language inside such
 * devices, though.  See <a href="http://sdcc.sourceforge.net">
 * http://sdcc.sourceforge.net</a> for one Linux-friendly
 * 8051 C/asm package you can use to create firmware images
 * in Intel Hex Format.  Good commercial IDEs also exist, as
 * well as software emulators.
 *
 * @version $Id: EZ.java,v 1.1 2001/05/16 00:43:07 dbrownell Exp $
 */
public final class EZ
{
    private Device	dev;

    //-------------------------------------------------------------

    private static boolean isFX2 (Device dev)
    {
	DeviceDescriptor	desc;

	if (dev == null)
	    return false;
	desc = dev.getDeviceDescriptor ();

	// Only the cypress development platform uses these IDs
	if (desc.getVendorId () == 0x04b4 && desc.getProductId () == 0x8613)
	    return true;

	// Other products will have IDs in (eep)rom ...

	return false;
    }

    //-------------------------------------------------------------

    private static void listDevices (Vector v)
    {
	for (int i = 0; i < v.size (); i++) {
	    Device	dev = (Device) v.elementAt (i);
	    System.out.println ("  " + dev.getPortIdentifier ());
	}
    }

    private static void usage ()
    {
	System.out.println ("usage: EZ [--portid id] firmware.ihx");
	System.out.println ("Devices are: ");
	try {
	    listDevices (getDevices ());
	} catch (IOException e) {
	    System.err.println ("** Exception: " + e.getMessage ());
	}
	System.exit (0);
    }

    private static Vector getDevices ()
    throws IOException
    {
	Vector	devices = new Vector (2, 5);
	Bus	bus [] = HostFactory.getHost ().getBusses ();
	Device	dev;

	for (int busnum = 0; busnum < bus.length; busnum++) {
	    for (int addr = 1; addr < 128; addr++) {
		dev = bus [busnum].getDevice (addr);
		if (!isFX2 (dev))
		    continue;
		devices.addElement (dev);
	    }
	}
	return devices;
    }

    private static Device getDefaultDevice ()
    throws IOException
    {
	Vector	devices = getDevices ();

	switch (devices.size ()) {
	case 1:
	    return (Device) devices.elementAt (0);
	case 0:
	    System.err.println ("no de FX2 devices");
	    break;
	default:
	    System.err.println ("which FX2 device?");
	    listDevices (devices);
	    break;
	}
	return null;
    }

    private static Device getDevice (String portId)
    throws IOException
    {
	try {
	    Device dev = HostFactory.getHost ().getDevice (portId);
	    if (isFX2 (dev))
		return dev;
	} catch (IllegalArgumentException e) {
	}
	System.err.println ("no such FX2 device: " + portId);
	return null;
    }

    //-------------------------------------------------------------

    // EZ [--portid N] firmware.ihx

    /**
     * Downloads firmware to devices.
     * Usage: <em>EZ [--portid id] firmware.ihx</em>
     */
    public static void main (String argv [])
    {
	Device dev = null;

	try {
	    switch (argv.length) {
	    case 3:
		if (!"--portid".equals (argv [0]))
		    usage ();
		dev = getDevice (argv [1]);
		break;
	    case 1:
		dev = getDefaultDevice ();
		break;
	    }
	} catch (IOException e) {
	    System.err.println ("** Exception: " + e.getMessage ());
	}
	if (dev == null)
	    usage ();
	
	System.out.println ("you want results?");
    }


    //-------------------------------------------------------------

    /**
     * Wraps the device; caller must guarantee that this device
     * supports EZ-USB firmware download primitives.
     * Currently "knows" only about the FX2.
     * The earlier "FX" model (USB-1.1 only) could also be
     * supported through this API.
     */
    public EZ (Device dev)
    {
	this.dev = dev;
    }

    private void writeMemory (short address, byte buf [])
    throws IOException
    {
	if (buf.length == 0)
	    return;

	ControlMessage	message = new ControlMessage ();
	byte		type = ControlMessage.TYPE_VENDOR;

	// readMemory() -- just change to DIR_TO_HOST
	type |= ControlMessage.DIR_TO_DEVICE;
	type |= ControlMessage.RECIPIENT_DEVICE;

	message.setRequestType (type);
	message.setRequest ((byte) 0xA0);	// firmware load
	message.setValue (address);
	// message.setIndex (0);
	message.setBuffer (buf);

	dev.control (message);
    }

    private void setReset (boolean value)
    throws IOException
    {
	byte b = (byte) (value ? 1 : 0);

	// E600 == CPUCS, CPU Control and Status
	// bit 0 controls reset
	writeMemory ((short)0xE600, new byte [] { b });
    }

    /**
     * Firmware is downloaded to EZ-USB devices in segments.
     */
    static class FirmwareRecord
    {
	/** Where in the 64K memory address space to write this data */
	public short	addr;

	/** Data to send */
	public byte	data [];
    }

    private void download (Vector v)
    throws IOException
    {
	int		len = v.size ();

	// could check for errors in firmware addresses ...
	// only three regions are legal:
	// - 8 KBytes code/data at 0
	// - 512 bytes data at 0xe000
	// - CPUCS register
	// the FX talks the same protocol; details are different.

	// FIXME sort records and merge adjacent ones;
	// 16 bytes/record and one I/O per ms (max) is
	// really slow.  One I/O per segment should work,
	// at least for segments up to 4 KB.

	setReset (true);
	for (int i = 0; i < len; i++) {
	    FirmwareRecord	fw = (FirmwareRecord) v.elementAt (i);

	    if (fw.addr == 0xffff)
		break;
	    writeMemory (fw.addr, fw.data);
	}
	setReset (false);
	// renumeration normally happens in a moment
    }

    private byte getByte (InputStream ihx, byte bytes [])
    throws IOException
    {
	int	temp = ihx.read (bytes, 0, 2);

	if (temp == 0)
	    return -1;
	if (temp != 2)
	    throw new IOException ("bad read");

	// to hex, return
	return 0;
    }

    /**
     * Downloads firmware to the device, normally causing it to
     * reset and re-enumerate under control of that firmware.
     * After downloading firmware, neither this wrapper nor the
     * {@link Device} it wraps should be used again.  A new device
     * will then appear at that location in the USB device tree.
     *
     * @param ihx firmware, in Intel Hex format.
     */
    public void download (InputStream ihx)
    throws IOException
    {
	byte	scratch [] = new byte [2];
	Vector	v = new Vector ();

	// this isn't very space-efficient.

	// all lines are like:  ":0305F800020053AB"
	for (;;) {
	    int			temp, len, xsum;
	    FirmwareRecord	r = new FirmwareRecord ();

	    // (a) a colon (':'), one character
	    // maybe preceding line's EOL is first
	    do {
		temp = ihx.read ();
	    } while (temp != ':' && Character.isWhitespace ((char)temp));

	    if (temp == 0)	// EOF?
		break;

	    // (b) the data length, two characters
	    len = getByte (ihx, scratch);
	    xsum = len;
	    r.data = new byte [len];

	    // (c) the start address, four characters
	    r.addr = getByte (ihx, scratch);
	    xsum += r.addr;
	    temp = getByte (ihx, scratch);
	    xsum += temp;
	    r.addr += temp << 8;

	    // (d) the record type, two characters [ignored]
	    xsum += getByte (ihx, scratch);

	    // (e) the data, two times the data length characters
	    for (int i = 0; i < len; i++)
		r.data [i] = getByte (ihx, scratch);

	    // (f) the checksum, two characters
	    xsum += getByte (ihx, scratch);
	    if ((xsum & 0xff) != 0)
		throw new IOException ("corrupt hex input file");
	    
	    v.addElement (r);
	}
	download (v);
    }
}
