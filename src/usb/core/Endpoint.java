/*
 * Java USB Library
 * Copyright (C) 2000 by David Brownell
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

package usb.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * Provides access to a USB endpoint descriptor, structuring device
 * data input or output in a given device configuration.
 * Only one thread at a time may use an endpoint for I/O.
 *
 * <p> Bulk endpoints look like standard byte I/O streams.
 * Interrupt endpoints send and receive short buffers directly; at this
 * writing there is no asynchronous notification framework.
 * ISO endpoints are not currently supported here.
 * ({@link ControlMessage}s are effectively device methods.)
 *
 * <p> Most fields of this descriptor are specified in section 9.6.4 of
 * the USB 1.1 specification.
 *
 * @version $Id: Endpoint.java,v 1.10 2002/04/19 21:56:11 dbrownell Exp $
 */
final public class Endpoint extends Descriptor
{
    /** Interface to which this endpoint belongs */
    private Interface		iface;

    private transient DeviceSPI	spi;

    // package private
    Endpoint (Interface intf, byte buf [])
    {
	super (buf);
	iface = intf;
	if (getDescriptorType () != TYPE_ENDPOINT)
	    throw new IllegalArgumentException ();
    }

    // package private
    Endpoint (Interface intf, int offset)
    {
	super (intf, offset);
	iface = intf;
	if (getDescriptorType () != TYPE_ENDPOINT)
	    throw new IllegalArgumentException ();
    }

    // ACCESS TO ALL DESCRIPTOR FIELDS

    /** Returns the endpoint address (a number from 0 to 15). */
    public int getEndpointAddress ()
	{ return 0x0f & getEndpoint (); }
    
    /**
     * Returns true if this is an input endpoint (data flows to host),
     * false if it is instead an output endpoint (data flows to device).
     */
    public boolean isInput ()
	{ return (getEndpoint () & 0x80) != 0; }
    
    /**
     * Returns an identifier composing the endpoint address
     * and the flag controlling its input.
     */
    public int getEndpoint () { return getU8 (2); }

    /** Returns the endpoint attributes (a bitmask describing its type). */
    public int getAttributes ()
	{ return getU8 (3); }
    
    /**
     * Returns the type of endpoint ("bulk", "iso", or "interrupt";
     * "control" won't normally be seen).
     */
    public String getType ()
    {
	switch (getAttributes () & 0x03) {
	    case 0: return "control";
	    case 1: return "iso";
	    case 2: return "bulk";
	    case 3: return "interrupt";
	}
	// "can't happen"
	return null;
    }

    /**
     * Maximum packet size this endpoint can send or receive.
     * Note that for "high bandwidth" mode (some USB 2.0 periodic endpoints)
     * this packet size accomodates the "multiplier".
     */
    public int getMaxPacketSize ()
    {
	int	field = getU16 (4);
	int	size = field & 0x3ff;
	String	type = getType ();
	boolean	highspeed = iface.getDevice ().getSpeed () == "high";

	// "high bandwidth" mode may use multiple packets per microframe
	if (highspeed && (type == "iso" || type == "interrupt"))
		size *= 1 + (field >> 11) & 0x03;
	return size;
    }
    
    /**
     * Returns interrupt polling interval (in <em>micro</em>seconds).
     * Polling intervals are typically measurable in milliseconds,
     * except that high speed periodic transactions may be polled
     * in intervals smaller than a frame.
     *
     * <p> For high speed bulk or contrul OUT endpoints, this value
     * exposes the maximum NAK rate of the endpoint.
     */
    public int getInterval ()
    {
    	int	interval = getU8 (6);
	String	type = getType ();
	boolean	highspeed = iface.getDevice ().getSpeed () == "high";

	if (type == "iso" || highspeed) {
		if ((type == "bulk" || type == "control") && interval == 0)
			/* never NAKs */ ;
		else
			interval = 1 << (interval - 1);
	}
	interval *= highspeed ? 125 : 1000;
	return interval;
    }
    

    // NOTE:  audio endpoint descriptors have 2 extra bytes.


    /** Returns the interface with which the endpoint is associated */
    public Interface getInterface ()
    {
	return iface;
    }

    public Device getDevice ()
    {
	return iface.getDevice ();
    }

    /**
     * Endpoint feature selector that sets or clears the halt state.
     * This is also used as the bit position in the status word which
     * indicates the halted state.
     */
    public static final int ENDPOINT_HALT = 0;


    /**
     * Returns an input stream used to read from this bulk input stream.
     * This stream has a {@link Endpoint#getMaxPacketSize maximum
     * packet size}, which application protocols may rely on.
     */
    public InputStream getInputStream ()
    {
	if ("bulk" != getType () || !isInput ())
	    throw new IllegalArgumentException ();
	spi = getDevice ().getSPI ();
	return new BulkInputStream (spi, getEndpoint ());
    }

    // local-only: not serializable
    private static final class BulkInputStream extends InputStream
    {
	private int		ep;
	private DeviceSPI	spi;

	BulkInputStream (DeviceSPI spi, int e)
	    { this.spi = spi; ep = e; }

	public int read ()
	throws IOException
	{
	    byte temp [] = spi.readBulk (ep, 1);
	    return 0xff & temp [0];
	}

	public int read (byte buf [], int off, int len)
	throws IOException
	{
	    if (len < 0)
		throw new IllegalArgumentException ();

	    // extra copy forced by RMI

	    byte temp [] = spi.readBulk (ep, len);
	    System.arraycopy (temp, 0, buf, off, temp.length);
	    return temp.length;
	}
    }

    /**
     * Returns the output stream used to write to this bulk output stream.
     * This stream has a {@link Endpoint#getMaxPacketSize maximum
     * packet size}, which application protocols may rely on.
     */
    public OutputStream getOutputStream ()
    {
	if ("bulk" != getType () || isInput ())
	    throw new IllegalArgumentException ();
	spi = getDevice ().getSPI ();
	return new BulkOutputStream (spi, getEndpoint ());
    }
    
    // local-only: not serializable
    private static final class BulkOutputStream extends OutputStream
    {
	private int		ep;
	private DeviceSPI	spi;

	BulkOutputStream (DeviceSPI spi, int e)
	    { this.spi = spi; ep = e; }

	public void write (int value)
	throws IOException
	{
	    byte temp [] = new byte [] { (byte) value };
	    spi.writeBulk (ep, temp);
	}

	public void write (byte buf [], int off, int len)
	throws IOException
	{
	    if (off == 0 && len == buf.length)
		spi.writeBulk (ep, buf);
	    else {
		// extra copy forced by RMI
		byte temp [] = new byte [len];
		System.arraycopy (buf, off, temp, 0, len);
		spi.writeBulk (ep, temp);
	    }
	}
    }

    /**
     * Blocks until an interrupt message is sent from device to host, and
     * then returns that message.  You must allocate a thread to poll
     * for such interrupts every {@link Endpoint#getInterval} milliseconds.
     * Examples of such messages include mouse,
     * keyboard, and joystick events.  (Hub events too, but the kernel
     * driver will always claim those.)
     *
     * <p> <em>Note:  not yet known to work on Linux; this starts with
     * the implementation model that's supposed to work. </em>
     *
     * @return the number of bytes actually read, or negative errno
     */
    public byte [] recvInterrupt ()
    throws IOException
    {
	if ("interrupt" != getType ())
	    throw new IllegalArgumentException ();
	if (spi == null)
	    spi = iface.getDevice ().getSPI ();
	// FIXME getInterval() ms timeout (caller guarantees periodicity)
	return spi.readIntr (getU8 (2), getMaxPacketSize ());
    }

    /**
     * Sends interrupt message from host to device.  The data, with a
     * {@link Endpoint#getMaxPacketSize maximum size},
     * is written in the next available
     * interrupt slot for this endpoint.  (Note that Interrupt "out"
     * capabilities were added in the 1.1 revision of the USB spec.)
     * An example of such interrupts:  at least one force-feedback
     * joystick uses them for the feedback.
     */
    public void sendInterrupt (byte buf [])
    throws IOException
    {
	if ("interrupt" != getType () || buf.length > getMaxPacketSize ())
	    throw new IllegalArgumentException ();
	if (spi == null)
	    spi = iface.getDevice ().getSPI ();
	// FIXME getInterval() ms timeout (caller guarantees periodicity)
	spi.writeIntr (getU8 (2), buf);
    }

    /**
     * Clears a halt status (stall) on the bulk endpoint.
     */
    public void clearHalt () throws IOException
    {
	if ("bulk" != getType ())
	    throw new IllegalArgumentException ();
	
	// this affects the USB data toggle and other HCD state,
	// otherwise we could issue control requests directly
	if (spi == null)
	    spi = iface.getDevice ().getSPI ();
	spi.clearHalt ((byte) (0x8f & getU8 (2)));
    }
}
