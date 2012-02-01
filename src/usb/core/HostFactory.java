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

package usb.core;

import java.io.IOException;

/**
 * Bootstrapping methods.
 */
public abstract class HostFactory
{
    static private Host		self;

	static protected int matchUSBvendorID = -1;//default any vendor ID
	static protected int matchUSBproductID = -1;//default any product ID

	/**
	 * Unlike Linux usbdevfs, the underlying jusb driver implementations for
	   Windows and Mac OS X won't always have access to all devices.  The drivers
	   are likely to use the Common Class Specification for filtering for
	   certain VendorIDs, and if desired, Vendor&ProductIDs	combinations,
	   or even Vendor/Product/Interface/ConfigurationID combinations.

	   Since we don't want to add vendor & product ID arguments to all
	      of the createHost() API's when they may not be needed on all platforms,
		  these setVendorIDmatchFilter() and setProductIDmatchFilter()
		  can be called BEFORE getHost()/createHost() to pre-initialize
		  the match filters as necessary for Windows and Mac OS X drivers.

		Setting ID to -1 should match all vendors (the default)  */
	public static void setVendorIDmatchFilter(int new_vendorID) {
		matchUSBvendorID = new_vendorID;
	}
	/** Setting ID to -1 should match all products (the default)  */
	public static void setProductIDmatchFilter(int new_productID) {
		matchUSBproductID = new_productID;
	}
    /**
     * Not part of the API.
     * This is part of the SPI for the reference implementation.
     */
    protected HostFactory () { }

    /**
     * Not part of the API.
     * This is part of the SPI for the reference implementation.
     */
    public abstract Host createHost () throws IOException;

	public Host createHost (USBListener startup_listener)
	throws IOException {
		return createHost();
	};


    /**
     * Returns a USB Host according to an environment-specific policy.
     * This bootstrapping method is part of the API, but the policy
     * used by the environment isn't.
     *
     * @exception IOException When USB Host functions are not available.
     */
    public static Host getHost ()
    throws IOException
	{
		return HostFactory.getHost(null);
	}

    public static Host getHost (USBListener startup_listener)
    throws IOException
    {
	synchronized (HostFactory.class) {
	    if (self != null)
		return self;

	    // The exact policy used to find a host to return is
	    // NOT standardized -- embedded systems might want
	    // to avoid reflection (at some cost in portability),
	    // others might be driven by some system property.

	    try {
		// prefer any designated proxy
		self = maybeGetHost ("usb.remote.RemoteHostFactory",startup_listener);
		if (self != null)
		    return self;

	    // ignore exceptions here
	    } catch (IOException e) {
	    } catch (RuntimeException e) {
	    }

	    // else try a native implementation
            String osname = System.getProperty ("os.name");

            if (osname.startsWith ("Windows")
                && !osname.startsWith ("Windows 95")
                && !osname.startsWith ("Windows 98")
                && !osname.startsWith ("Windows ME")) {
			//only Windows 2000, XP, and future XP based OS's supported for now
                osname="Windows";
            } else if (osname.startsWith ("Mac OS X")) {
			// really expect at least 10.1.2 for cyclePort() implementation
                osname="MacOSX";
                    //System.out.println("Trying usb.macosx.MacOSX");
            }
            self = maybeGetHost ("usb." + osname.toLowerCase () + "." + osname,startup_listener);

	    if (self == null)
		throw new IOException ("USB Host support is unavailable for this OS.");
	}
	return self;
    }

    static private Host maybeGetHost (String classname,USBListener startup_listener)
    throws IOException, SecurityException
    {
	try {
	    Object	temp;
	    HostFactory	factory;

	    temp = Class.forName (classname);
	    temp = ((Class)temp).newInstance ();
	    return ((HostFactory) temp).createHost (startup_listener);

	} catch (SecurityException e) {
	    throw e;

	} catch (IOException e) {
	    throw e;

	} catch (Exception e) {
	    return null;
	}
    }
}
