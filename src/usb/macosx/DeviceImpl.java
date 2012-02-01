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

package usb.macosx;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Locale;

import usb.core.*;
import usb.util.LangCode;


/**
 * Provides access to a USB device.  To use a device, first make sure that
 * you can use the selected configuration.
 *
 * @author David Brownell
 * @author Wayne Westerman
 * @version $Id: DeviceImpl.java,v 1.2 2005/01/17 07:17:40 westerma Exp $
 * @version $Id: DeviceImpl.java,v 1.2 2005/01/17 07:17:40 westerma Exp $
 */
public final class DeviceImpl extends Device implements DeviceSPI {
    // DEFERRED FUNCTIONALITY:
    // - Anything for iso support, including synchFrame control msg
    // - Configuration changing (broken support exists)
    // - Alternate settings (incomplete support exists)


    /** the bus we're connected to */
    private final USB      usb;

    private String      path;

    // Used for internal synchronization; it should only be known
    // within this class.  Protects string cache and configuration.
    private final Object   lock = new Object ();

    // XXX Our record of the configuration could be out of date
    // since the kernel doesn't yet protect anyone from changes to
    // it, even driver software claiming interfaces exposed by
    // the current configuration.

    private int         selectedConfig = -1;
    private Configuration  currentConfig;

    private DeviceDescriptor  descriptor;

    private boolean     checkedStrings;
    private int         languages [];

    // cache just one language; key = Byte (id)
    private int         cachedLanguage;
    private Hashtable      stringCache;

    /** for hub nodes, lists children; else null */
    private DeviceImpl     children [];

    private DeviceImpl     hub;
    private int         hubPortNum;


    // XXX Need kernel support for some device lock to safeguard
    // devices against unexpected concurrent operations.

    // package private
    DeviceImpl (USB bus, String drivername, int a)
    throws IOException, SecurityException
    {
        super (null, bus, a);

        usb = bus;
        path = drivername;//f.getPath ();

        if(MacOSX.trace)
            System.err.println("in usb.macosx.DeviceImpl()\n");
        // should only fail if the device unplugged before
        // we opened it, or permissions were bogus, or ...
        long openstat = openNative (path);
        if (openstat < 0) {
            String  message = "JUSB can't open device r/w, " + path;
            throw new USBException (message, (int)(-openstat));
        } else {
            fd = (int)openstat;  //openNative() returns a DLL device handle between 1 & 127 on success
        }

        int num_descriptor_retries = 0;
        IOException descriptor_exception = null;
        do {
                // fd's open; NOW we can get the device descriptor
                try {
                        byte buf [];

                        buf = ControlMessage.getStandardDescriptor (this,
                                                                                                                Descriptor.TYPE_DEVICE, (byte) 0, 0, 18);
                        descriptor = new DeviceDescriptor (this, buf);
                        // ... and configuration descriptor
                        getConfiguration ();

                        if (MacOSX.trace)
                                System.err.println ("new: " + path);
                        return;
                } catch (USBException ue) {
                        if (MacOSX.debug)
                                ue.printStackTrace ();
                        else if(MacOSX.trace)
                                System.err.println ("get dev descr fail:  "
                                                                        + path
                                                                        + ", "
                                                                        + ue.getMessage ());
                        descriptor_exception = ue;
                } catch (IOException e) {
                        if (MacOSX.debug)
                                System.err.println ("get dev descr fail:  "
                                                                        + path
                                                                        + ", "
                                                                        + e.getMessage ());
                        throw e;
                }
        } while ( num_descriptor_retries++ < 4 );
        throw descriptor_exception;
    }

    public String toString ()
    {
        StringBuffer   buf = new StringBuffer ("{MacOSX Device: ");
        String      prod = descriptor.getProduct (0);

        buf.append (path);
        if (prod != null) {
            buf.append (" ");
            buf.append (prod);
        }
        buf.append ("}");
        return buf.toString ();
    }

    /** Releases any unreleased system resources. */
    protected void finalize ()
    throws USBException
    { close ();}

    /**
     * Immediately closes the device; further operations on this object will
     * fail.  This is normally done only when the device is being removed.
     */
    // package private
    void close ()
    throws USBException
    {
        if (fd < 0)
            return;

        try {
            // make sure this isn't usable any more
            long status = closeNative (fd);
            if (status < 0)
                throw new USBException ("error closing device",
                                       (int)(-status));
        } finally {
            // make sure nobody else sees the device
            usb.removeDev (this);
            hub = null;
            fd = -1;
        }
    }


    /*-------------------------------------------------------------------*/

    // implementations of abstract "Device" methods

    // FIXME !!
    public String getSpeed ()
    { throw new RuntimeException (); }

    public Device getHub ()
    { return hub;}

    public int getHubPortNum ()
    { return hubPortNum;}

    public int getNumPorts ()
    { return(children == null) ? 0 : children.length;}

    public DeviceDescriptor getDeviceDescriptor ()
    { return descriptor;}

    public Configuration getConfiguration ()
    throws IOException
    {
        if (selectedConfig == -1) {
            if (descriptor.getNumConfigurations () != 1) {
                ControlMessage msg = new ControlMessage ();

                msg.setRequestType ((byte)(msg.DIR_TO_HOST
                                           | msg.TYPE_STANDARD
                                           | msg.RECIPIENT_DEVICE
                                          ));
                msg.setRequest (msg.GET_CONFIGURATION);
                msg.setValue ((short) 0);
                msg.setIndex ((short) 0);
                msg.setLength (1);
                control (msg);
                selectedConfig = 0xff & msg.getBuffer ()[0];
            } else
                selectedConfig = 0;
        }
        return getConfiguration (selectedConfig);
    }

    // also a DeviceSPI method
    public Device getChild (int port)
    {
        if (children == null)
            return null;
        return children [port - 1];
    }

    /*-------------------------------------------------------------------*/


    /**
     * Returns the filesystem name for this file.
     */
    public String getPath () { return path;}

    // uses the system locale ...
    // perfect for server/implementation, maybe not for clients.
    // Locale is also not available on all systems; may need
    // to ignore this if no I18N support is available/used.

    private int chooseDefaultLanguage ()
    {
        if (languages.length == 1)
            return languages [0];

        Locale   dflt = Locale.getDefault ();
        int   retval = languages [0];

        for (int i = 0; i < languages.length; i++) {
            Locale  current = LangCode.getLocale (languages [i]);

            // return exact matches if possible
            if (current == dflt)
                return languages [i];

            // insist on shared language
            if (current == null)
                continue;
            if (!current.getLanguage ().equals (dflt.getLanguage ()))
                continue;

            // could also check country (return now if same)

            // it's OK, and maybe better than our last guess
            retval = languages [i];
        }
        return retval;
    }

    /**
     * Returns the string indexed with the specified ID in the
     * default language, or null if there is no such string.
     * Use of cached values is preferred.
     *
     * <p>The default language is the one that is being cached.
     * If this is the first request, then the language of the
     * default locale is used if it is supported, else the first
     * supported language is chosen as a fallback.
     */
    public String getString (int id)
    throws IOException
    {
        if (!checkedStrings)
            getLanguages ();
        if (languages == null || languages.length == 0)
            return null;
        if (stringCache == null)
            return getString (id, chooseDefaultLanguage ());
        else
            return getString (id, cachedLanguage);
    }

    /**
     * Implementation of {@link usb.core.Device#getString Device.getString}
     * which can cache strings in the device's default language.
     */
    public String getString (int id, int language)
    throws IOException
    {
        if (id == 0)
            return null;
        if (id < 0 || id > 0xff)
            throw new IllegalArgumentException ();
        if (!checkedStrings)
            getLanguages ();
        if (languages == null || languages.length == 0)
            return null;

        Byte  key = new Byte ((byte) id);
        String   retval = null;

        if (stringCache == null) {
            synchronized (lock) {
                if (stringCache == null) {
                    cachedLanguage = language;
                    stringCache = new Hashtable (7);
                }
            }
/**/
        } else if (stringCache.containsKey (key)) {
            Object  value = stringCache.get (key);

            if (value instanceof String)
                return(String) value;
            else
                return null;
/**/
        }

        retval = ControlMessage.getString (this, (byte) id, language);

        if (retval == null)  // negative caching
            stringCache.put (key, Boolean.FALSE);
        else        // positive caching
            stringCache.put (key, retval);
        return retval;
    }

    public int [] getLanguages ()
    throws IOException
    {
        synchronized (lock) {
            if (!checkedStrings)
                languages = ControlMessage.getLanguages (this);
            checkedStrings = true;    //if don't set this can get repetitive lookup failures?  -- Wayne Westerman
        }

        if (languages == null)
            return null;

        int retval [] = new int [languages.length];
        for (int i = 0; i < languages.length; i++)
            retval [i] = languages [i];
        return retval;
    }

    /**
     * Returns the specified configuration.
     * This can need to accumulate device-specific smarts.
     */
    public Configuration getConfiguration (int index)
    throws IOException
    {
        Configuration  retval;

        if (index < 0 || index >= descriptor.getNumConfigurations ())
            throw new IllegalArgumentException ();

        synchronized (lock) {
            if (index == selectedConfig && currentConfig != null)
                return currentConfig;

            // NOTE:  we _could_ read 'fd' and bypass control messages
            // for the case of the current configuration

            retval = new Configuration (this, getConfigBuf (index));
            if (index == selectedConfig)
                currentConfig = retval;
        }
        return retval;
    }


    /**
     * Assigns a device configuration, which must be one of those
     * supported by this device.
     *
     * @deprecated
     * <em><font color=red>Consider this dangerous.</font></em>
     */
    public void setConfiguration (int index)
    throws USBException
    {
        long status;

        if (index < 0 || index > descriptor.getNumConfigurations ())
            throw new IllegalArgumentException ();

		//throw new USBException ("can't set configuration (unimplemented)", 0);
        synchronized (lock) {
            if ((status = setConfiguration (fd, index)) < 0)
                throw new USBException ("can't set configuration", (int)-status);
            if (selectedConfig != index) {
                selectedConfig = index;
                currentConfig = null;
            }
        }
    }


    // usbdevfs-imposed limitation; USB limit is 64KB

    private static final int MAX_CONTROL_LENGTH = 4096;


    public byte [] readControl (byte type, byte request,
                                short value, short index, short length)
    throws IOException
    {
        byte  data [] = new byte [length & 0xffff];
        long   status;

        if (length >= MAX_CONTROL_LENGTH
            || (type & ControlMessage.DIR_TO_HOST) == 0)
            throw new IllegalArgumentException ();
        if (fd < 0) {
            throw new USBException("Dev.readControl aborted",-USBException.IO_NotOpen);
        }
        if (MacOSX.trace)
            System.out.println (path
                                + ": Dev.readControl, rqt 0x" + Integer.toHexString (0xff & type)
                               + ", req 0x" + Integer.toHexString (0xff & request)
                               + ", value 0x" + Integer.toHexString (0xffff & value)
                               + ", index 0x" + Integer.toHexString (0xffff & index)
                               + ", len " + Integer.toString (0xffff & length)
                               );

        status = controlMsg (fd, type, request, value, index,
                             data, 0, (short) data.length);
        if (status >= 0) {
			int length_when_done = (int) status;
            if (length_when_done != data.length) {
                byte temp [] = new byte [length_when_done];
                System.arraycopy (data, 0, temp, 0, length_when_done);
                data = temp;
            }
            return data;
        } else throw new USBException ("control read error", (int)(-status));
    }

    public void writeControl (byte type, byte request,
                              short value, short index, byte buf [])
    throws IOException
    {
        if (fd < 0) {
            throw new USBException("Dev.writeControl aborted",-USBException.IO_NotOpen);
        }
        if (buf == null) {
            //assume we're doing a No-Data-Control, and somebody has to make a 0-length buf
            buf = new byte[0];
        }
        if (buf.length >= MAX_CONTROL_LENGTH
            || (type & ControlMessage.DIR_TO_HOST) != 0)
            throw new IllegalArgumentException ();

        if (MacOSX.trace)
            System.out.println (
                               "Dev.writeControl, rqt 0x" + Integer.toHexString (0xff & type)
                               + ", req 0x" + Integer.toHexString (0xff & request)
                               + ", value 0x" + Integer.toHexString (0xffff & value)
                               + ", index 0x" + Integer.toHexString (0xffff & index)
                               + ", len " + Integer.toString (buf.length)
                               );

        long status = controlMsg (fd, type, request, value, index,
                                 buf, 0, (short) buf.length);

        if (status < 0) {
            throw new USBException ("control write error", (int)(-status));
    }
    }

    public byte [] getConfigBuf (int n)
    throws IOException
    {
        byte     buf [];
        Configuration  config;
        int      total;

        // start by reading just the configuration descriptor
        buf = ControlMessage.getStandardDescriptor (this,
                                                    Descriptor.TYPE_CONFIGURATION,
                                                    (byte) n, 0, 9);
        config = new Configuration (this, buf);

        // return ALL descriptors (interface, endpoint, ...)
        if ((total = config.getTotalLength ()) != buf.length) {
            // WATCH FOR:  devs not handling this post-enumeration
            buf = ControlMessage.getStandardDescriptor (this,
                                                        Descriptor.TYPE_CONFIGURATION,
                                                        (byte) n, 0, total);
        }
        return buf;
    }

    public boolean cyclePortWorks() {
		return true;
    }
    public int cyclePort() {
        long status = cyclePort(fd);
        //if (status < 0)
          //  throw new USBException ("cyclePort error", (int)(-status));

        return (int)status;
    }

    /*-------------------------------------------------------------------*/

    /*
     * Native code support.
     * All these methods return negative LONG errno on error, since Mac OS X IOReturn codes can be full 32-bit
     *
     */

    /** handle into pool of devices managed by underlying jusbmacosx native dll */
    private int      fd;



    /** Opens the usb devfs file.  */
    private static native long openNative (String filename);
    private static native long openExclusiveNative (int fd);

    /** Closes the native file descriptor.  */
    private static native long closeNative (int fd);

	/** Tells OS to detach and re-attach specified device */
	private static native long cyclePort(int fd);

    private static native long controlMsg (int fd,
                                          byte requestType, byte request,
                                          short value, short index,
                                          byte buf [], int off, short length);


    /** Assigns the specified configuration as current. */
    private static native long setConfiguration (int fd, int config);

    // XXX some of these I/O methods should pass timeouts
    // to support some sort of clean activity shutdown
    // (policy in the Java layer, not inside this JNI glue)
    // (glue policy is a 10 second timeout at this writing)


    private static native long readBulk (int fd, int ep, byte buf [], int off, int length);

    // this is the API imposed by RMI.
    // forces an extra rx copy, also heap access
    public byte [] readBulk (int ep, int length)
    throws IOException
    {
        byte retval [] = new byte [length];
        //throw new USBException ("readBulk (unimplemented!)", 0);
        long result = readBulk (ep, retval, 0, length);
        //int result = readBulkBlocks (ep, retval, 0, length);

        if (result < 0)
            throw new USBException ("readBulk", (int)-result);
        if (result != length) {
            int lenwdone = (int)result;
            byte temp [] = new byte [lenwdone];
            System.arraycopy (retval, 0, temp, 0, lenwdone);
            retval = temp;
        }
        return retval;
    }




    // this is the API we'd  LIKE to use:
    // minimum # copies, heap is left alone
    public int readBulk (int ep, byte buf [], int off, int length) throws IOException
    {
   // devfs currently maxes out at 4KB bulk transfers
   //throw new USBException ("readBulk (unimplemented!)", 0);


   int result = 0;
   while (length > 0) {
       int this_transfer = Math.min (length, 4096);
           long temp = readBulk (fd, ep, buf, off, this_transfer);

       // error ... discarding how much we've read
       if (temp < 0)
                return (int)temp;
           off += (int)temp;
           length -= (int)temp;
           result += (int)temp;

       // short reads should return
           if ((int)temp < this_transfer)
      break;
   }
       return result;
    }


    private static native long writeBulk (int fd, int ep,byte buf [], int off, int length);

    // this is the API imposed by RMI.
    // usually forces an extra tx copy, and heap access
    public void
    writeBulk (int ep, byte buf [])
    throws USBException
    { writeBulk (ep, buf, 0, buf.length);}

    // this is the API we'd  LIKE to use:
    // minimum # copies, heap is left alone
    public void
    writeBulk (int ep, byte buf [], int off, int length)
    throws USBException
    {
        // devfs currently maxes out at 4KB bulk transfers
        long result = 0;
		//throw new USBException ("writeBulk (unimplemented)", -result);

        while (length > 0) {
            int this_transfer = Math.min (length, 4096);
            result = writeBulk (fd, ep, buf, off, this_transfer);
            if (result < 0)
                throw new USBException ("writeBulk", (int)-result);
            off += this_transfer;
            length -= this_transfer;
        }
    }



    private static native long readIntr (int fd, int ep, byte buf [], int off, int length);

    public byte [] readIntr (int ep, int length)
    {
        byte retval [] = new byte [length];
        //long len = readBulk (fd, ep, retval, 0, length);
        long len = readIntr (fd, ep, retval, 0, length);
        if (len < 0) {
            return null;
        } else if (len != length) {
            byte temp [] = new byte [(int)len];
            System.arraycopy (retval, 0, temp, 0, (int)len);
            retval = temp;
        }
        return retval;
        /*//throw new USBException ("readIntr (unimplemented)", 0);
		return null;*/
    }


    private static native long writeIntr (int fd, int ep,byte buf [], int off, int length);

    public void
    writeIntr (int ep, byte buf [])
    throws USBException
    {
         long retval = writeIntr (fd, ep, buf, 0, buf.length);
        //int retval = writeBulk (fd, ep, buf, 0, buf.length);
        if (retval < 0)
            throw new USBException ("writeIntr", (int)-retval);
        //throw new USBException ("writeIntr (unimplemented)", 0);
    }


    private static native long clearHalt (int fd, byte ep);

    // package private
    public
    int clearHalt (byte ep) {
		return (int)clearHalt (fd, ep);
	}


    private static native long claimInterface (int fd, int ifno);

    /**
     * Claims this interface, so that no other driver can.
     */
    public void claimInterface (int ifno)
    throws IOException
    {
        long val = claimInterface (fd, ifno);
        if (val < 0)
            throw new USBException ("claimInterface", (int)-val);
        //throw new USBException ("claimInterface (unimplemented)", 0);
    }



    // usbdevfs is done using this interface
    private static native long releaseInterface (int fd, int ifno);

    /**
     * Releases an interface claim.
     */
    public void releaseInterface (int ifno)
    throws IOException
    {
        long val = releaseInterface (fd, ifno);
        if (val < 0)
            throw new USBException ("releaseInterface", (int)-val);
        //throw new USBException ("releaseInterface (unimplemented)", 0);
    }


    private static native long setInterface (int fd, int ifno, int alt);

    /**
     * Assigns an interface to an alternate setting.
     * <em>Note:</em>  alternate settings probably
     * aren't handled correctly yet.
     */
    public void setInterface (int ifno, int alt)
    throws IOException
    {
        long val = setInterface (fd, ifno, alt);
        if (val < 0)
		throw new USBException ("setInterface", (int)-val);
        //throw new USBException ("setInterface (unimplemented)", 0);
    }


    //private static native int getHubPorts (int fd, byte data []);


    // package private (for hubs)
    void updateChildren ()
    throws SecurityException
    {
        byte  data [] = new byte [128];
        throw new SecurityException ("updateChildren (hubs unimplemented)");

        /*synchronized (lock) {
            int  status = getHubPorts (fd, data);

            if (status < 0) {
                children = null;
                System.err.println ("bad hub port ioctl, errno " + -status);
                return;
            }
            if (children == null || (data [0] & 0x7f) != children.length)
                children = new DeviceImpl [data [0] & 0x7f];
            for (int i = 0; i < children.length; i++) {
                int      devnum = 0x7f & data [1 + i];
                DeviceImpl  old = children [i];

                if (MacOSX.debug && old != null
                    && old.hub != null && old.hub != this)
                    System.err.println ("old.hub not this: " + old.hub);

                if (devnum != 0) {
                    children [i] = (DeviceImpl) usb.getDevice (devnum);
                    if (children [i] != null && children [i].hub == null) {
                        children [i].hub = this;
                        children [i].hubPortNum = i + 1;
                        if (MacOSX.trace)
                            System.err.println ("usb-" + usb.getBusId ()
                                                + " hub dev" + getAddress ()
                                                + " port " + children [i].hubPortNum
                                                + " = dev" + children [i].getAddress ());
                    }

                    if (MacOSX.debug && children [i] != null
                        && children [i].hub != this)
                        System.err.println ("new.hub not this: "
                                            + children [i].hub);
                } else
                    children [i] = null;
            }
        } */
    }

    //private static native String getClaimer (int fd, int ifno);

    /**
     * Returns a system-specific string providing information
     * about the driver claiming this interface, or null.
     */
    public String getClaimer (int ifno)
    {
        return "FingerWorks Filter";//getClaimer (fd, ifno);
    }
}
