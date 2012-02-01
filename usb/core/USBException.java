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

/**
 * USBException objects indicate USB errors.
 *
 * @author David Brownell
 * @version $Id: USBException.java,v 1.8 2000/11/18 22:58:40 dbrownell Exp $
 */
public abstract class USBException extends IOException
{
    /**
     * Constructs an exception object representing a USB exception.
     */
    public USBException (String descriptive)
    {
	super (descriptive);
    }

    /**
     * Returns true iff the exception indicates a (bulk) endpoint has
     * stalled; these are used as error indicators in device protocols.
     */
    abstract public boolean isStalled ();
}
