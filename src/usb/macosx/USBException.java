/*
 * Java USB Library
 * Copyright (C) 2000 by David Brownell
 * Copyright (C) 2002 by Wayne Westerman
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option; any later version.
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


/**
 * USBException objects encapsulate OS error indicators as returned through
 * system calls on failed attempts to perform I/O operations.
 * Avoid those error numbers if you care about portable code, but
 * feel free to use the strings in diagnostics.
 *
 * @author David Brownell
 * @author Wayne Westerman
 * @version $Id: USBException.java,v 1.2 2005/01/17 07:17:40 westerma Exp $
 */
class USBException extends usb.core.USBException {
	/**
	 * OS-specific diagnostic error code being encapsulated.
	 * There's also an OS-specific diagnostic string.
	 * @see     getErrno
	 */
	private int     errnum;

	/**
	 * Constructs an exception object encapsulating a USB error number.
	 */
	public USBException (String descriptive, int errno)
	{
            super (new StringBuffer(descriptive).append(SEPARATOR).append(getErrnoString (errno)).append(" [0x").append(Integer.toHexString(errno)).append("] ").toString());
            this.errnum = errno;
	}

	/**
	 * Returns OS-specific error code encapsulated by this USB exception.
	 * @deprecated For portability, avoid using this function.
	 */
	public int getErrno () { return errnum;}


        /*
         * MacOSX/darwin error/return codes from IO_KIT/IO_Return.h and IO_Kit/usb/USB.h
         *
         */

        public static final int IO_Success = 0x0;
        public static final int IO_Error            = 0x2bc; // general error
        public static final int IO_NoMemory         = 0x2bd; // can't allocate memory
        public static final int IO_NoResources      = 0x2be; // resource shortage
        public static final int IO_NoDevice         = 0x2c0; // no such device
        public static final int IO_NotPrivileged    = 0x2c1; // privilege violation
        public static final int IO_BadArgument      = 0x2c2; // invalid argument
        public static final int IO_ExclusiveAccess  = 0x2c5; // exclusive access and
                                                         //   device already open
        public static final int IO_Unsupported      = 0x2c7; // unsupported function
        public static final int IO_VMError          = 0x2c8; // misc. VM failure
        public static final int IO_InternalError    = 0x2c9; // internal error
        public static final int IO_IO_Error          = 0x2ca; // General I/O error
                                                         //    public static final int IO_???Error       = 0x2cb; // ???
        public static final int IO_NotOpen          = 0x2cd; // device not open
        public static final int IO_NotReadable      = 0x2ce; // read not supported
        public static final int IO_NotWritable      = 0x2cf; // write not supported
        public static final int IO_NotAligned       = 0x2d0; // alignment error
        public static final int IO_StillOpen        = 0x2d2; // device(s) still open
        public static final int IO_Busy             = 0x2d5; // Device Busy
        public static final int IO_Timeout          = 0x2d6; // I/O Timeout
        public static final int IO_NotReady         = 0x2d8; // not ready
        public static final int IO_NotAttached      = 0x2d9; // device not attached
        public static final int IO_NotPermitted     = 0x2e2; // not permitted
        public static final int IO_NoPower          = 0x2e3; // no power to device
        public static final int IO_Underrun         = 0x2e7; // data underrun
        public static final int IO_Overrun          = 0x2e8; // data overrun
        public static final int IO_DeviceError	  = 0x2e9; // the device is not working properly!
        public static final int IO_NoCompletion	  = 0x2ea; // a completion routine is required
        public static final int IO_Aborted	  = 0x2eb; // operation aborted
        public static final int IO_NoBandwidth	  = 0x2ec; // bus bandwidth would be exceeded
        public static final int IO_NotResponding	  = 0x2ed; // device not responding
        public static final int IO_IsoTooOld	  = 0x2ee; // isochronous I/O request for distant past!
        public static final int IO_IsoTooNew	  = 0x2ef; // isochronous I/O request for distant future
        public static final int IO_NotFound         = 0x2f0; // data was not found
        public static final int IO_Invalid          = 0x1;   // should never be seen

        public static final int IO_USBUnknownPipeErr		 = 97; // 0x61  Pipe ref not recognised
        public static final int IO_USBTooManyPipesErr		 = 96; // 0x60  Too many pipes
        public static final int IO_USBNoAsyncPortErr		 = 95; // 0x5f  no async port
        public static final int IO_USBNotEnoughPipesErr 	 = 94; // 0x5e  not enough pipes in interface
        public static final int IO_USBNotEnoughPowerErr 	 = 93; // 0x5d  not enough power for selected configuration
        public static final int IO_USBEndpointNotFound		 = 87; // 0x57  Not found
        public static final int IO_USBConfigNotFound		 = 86; // 0x56  Not found
        public static final int IO_USBTransactionTimeout	 = 81; // 0x51  time out
        public static final int IO_USBTransactionReturned	 = 80; // 0x50  The transaction has been returned to the caller
        public static final int IO_USBPipeStalled		 = 79; // 0x4f  Pipe has stalled, error needs to be cleared
        public static final int IO_USBInterfaceNotFound		 = 78; // 0x4e  Interface ref not recognised

        /*  Will these (from IO_Kit/usb/USB.h)?  ever get passed back through IO_Kit user-level USB API?

            // USB Hardware Errors
            // These errors are returned by the OHCI controller.  The      in parenthesis (xx; corresponds to the OHCI Completion Code.
            // For the following Completion codes, we return a generic IO_Kit error instead of a USB specific error.
            //
            // Completion Code   	Error Returned		Description
            //     9		IO_Underrun	(Data Underrun; EP returned less data than max packet size
            //     8		IO_Overrun	(Data Overrun; Packet too large or more data than buffer
            //     5		IO_NotResponding  Device Not responding
            //     4		IO_USBPipeStalled	Endpoint returned a STALL PID
            //*/
        public static final int IO_USBLinkErr		 = 16;	//
        public static final int IO_USBNotSent2Err	 = 15; 	// Transaction not sent
        public static final int IO_USBNotSent1Err	 = 14; 	// Transaction not sent
        public static final int IO_USBBufferUnderrunErr	 = 13;	// Buffer Underrun (Host hardware failure on data out, PCI busy?;
        public static final int IO_USBBufferOverrunErr	 = 12;	// Buffer Overrun (Host hardware failure on data out, PCI busy?;
        public static final int IO_USBReserved2Err	 = 11;	// Reserved
        public static final int IO_USBReserved1Err	 = 10;	// Reserved
        public static final int IO_USBWrongPIDErr	 = 7;	// Pipe stall, Bad or wrong PID
        public static final int IO_USBPIDCheckErr	 = 6;	// Pipe stall, PID CRC error
        public static final int IO_USBDataToggleErr	 = 3;	// Pipe stall, Bad data toggle
        public static final int IO_USBBitstufErr	 = 2;	// Pipe stall, bitstuffing
        public static final int IO_USBCRCErr		 = 1;	// Pipe stall, bad CRC


	// separates different diagnostics
	private static final String SEPARATOR = " -- ";


	/**
	 * Returns descriptive diagnostic string for the OS error code
	 * encpasulated in this exception.  If it is one of the error codes
	 * known to be used by the USB subsystem, a USB-related diagnostic
	 * is provided.  Otherwise, this method returns the standard host
	 * operating system diagnostic.
	 * @deprecated For portability, avoid using this function.
	 */
        public String getErrnoString () {
            return getErrnoString(errnum);
        }
	public static String getErrnoString (int errno)
	{
		// this needs I18N support
            String      extra = null;
            StringBuffer strbuf = new StringBuffer(" [0x").append(Integer.toHexString(errno)).append("] ");


                //mask off system and subsystem fields of IOReturn type
            switch ( errno & 0x3FF ) {
                    case IO_NotPrivileged:
                    case IO_ExclusiveAccess:
                            extra = "Access Denied";
                            break;
                    case IO_NoDevice:
                            extra = "Device not found";
                            break;

                    case IO_NotReady://ENOENT
                            extra = "USB operation canceled";
                            break;
                    case IO_USBEndpointNotFound:	// ENXIO_
                            extra = "USB bad endpoint";
                            break;
                    //case 18:	// EXDEV
                    //	extra = "USB isochronous transfer incomplete";
                    //	break;
                    case IO_NotAttached:
                            extra = "USB device has been removed?";
                            break;
                    case IO_NoBandwidth:	// ENOSPC
                            extra = "USB bandwidth reservation would be exceeded";
                            break;
                    case IO_USBPipeStalled://EPIPE:
                            extra = "USB endpoint stall";
                            break;

                    case IO_Underrun://70:	// ECOMM
                    case IO_Overrun://70:	// ECOMM
                            extra = "USB buffer overflow or underflow";
                            break;
                    case IO_InternalError://71:	// EPROTO
                    case IO_IO_Error:
                            extra = "USB internal error";
                            break;
                        case IO_USBBufferOverrunErr://EOVERFLOW:
                            extra = "USB data overrun";
                            break;
                        case IO_USBWrongPIDErr:
                    case IO_USBDataToggleErr:
                        case IO_USBPIDCheckErr:
                        case IO_USBBitstufErr:
                        case IO_USBCRCErr://84:	// EILSEQ
                            extra = "USB CRC or data toggle error";
                            break;
                    case IO_USBUnknownPipeErr://108:	// ESHUTDOWN
                            extra = "USB host controller shut down";
                            break;
                    case IO_NotResponding://ENODEV:
                            extra = "USB device not responding";
                            break;
                        case IO_USBTransactionTimeout:
                        case IO_Timeout:
                            extra = "USB operation timed out";
                            break;
                    case IO_USBTransactionReturned://115:	// EINPROGRESS
                            extra = "USB operation pending";
                            break;
                    case IO_USBBufferUnderrunErr:
                            extra = "USB data underrun or short packet";
                            break;
            }

            if ( extra != null )
                strbuf.insert(0,extra);

            return strbuf.toString();
/*	// strError uses the I18N support of the C library
                    if (extra == null;
                        return strError (errno;;
                                         else
                                         return extra + SEPARATOR + strError (errno);*/
	}


	/**
	 * Returns true iff the exception indicates an endpoint which has
	 * stalled; these are often used as protocol error indicators.
	 */
	public boolean isStalled ()
	{ return IO_USBPipeStalled == errnum;}

	/**
	 * Returns a platform-specific diagnostic message.
	 */
/*	public String getMessage ()
	{
		return super.getMessage ()
		+ SEPARATOR
		+ getErrnoString ()
		+ " [" + errno + "] "
		;
	}*/

	// FIXME ... indirect through something

	// wrapper for strerror(3)
	//private native String strError (int errno);
}
