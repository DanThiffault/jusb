/*
 * Java USB Library for Mac OS X 10.1.2
 * Copyright (C) 2002-2004 by Wayne Westerman
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
/*
    File:		USBPrivateDataSample.c

    Description:	This sample demonstrates how to use IOKitLib and IOUSBLib to set up asynchronous
                        callbacks when a USB device is attached to or removed from the system.
                        It also shows how to associate arbitrary data with each device instance.

    Copyright:		© Copyright 2001 Apple Computer, Inc. All rights reserved.

    Disclaimer:		IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc.
                        ("Apple") in consideration of your agreement to the following terms, and your
                        use, installation, modification or redistribution of this Apple software
                        constitutes acceptance of these terms.  If you do not agree with these terms,
                        please do not use, install, modify or redistribute this Apple software.

                        In consideration of your agreement to abide by the following terms, and subject
                        to these terms, Apple grants you a personal, non-exclusive license, under Apple’s
                        copyrights in this original Apple software (the "Apple Software"), to use,
                        reproduce, modify and redistribute the Apple Software, with or without
                        modifications, in source and/or binary forms; provided that if you redistribute
                        the Apple Software in its entirety and without modifications, you must retain
                        this notice and the following text and disclaimers in all such redistributions of
                        the Apple Software.  Neither the name, trademarks, service marks or logos of
                        Apple Computer, Inc. may be used to endorse or promote products derived from the
                        Apple Software without specific prior written permission from Apple.  Except as
                        expressly stated in this notice, no other rights or licenses, express or implied,
                        are granted by Apple herein, including but not limited to any patent rights that
                        may be infringed by your derivative works or by other works in which the Apple
                        Software may be incorporated.

                        The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO
                        WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED
                        WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR
                        PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN
                        COMBINATION WITH YOUR PRODUCTS.

                        IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR
                        CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
                        GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
                        ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION
                        OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT
                        (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN
                        ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

    Change History (most recent first):

            <2>		04/24/02	Added comments, release of interface object, use of USB location ID
            <1>	 	10/30/01	New sample.

*/

//================================================================================================
//   Includes
//================================================================================================
//
#include <CoreFoundation/CoreFoundation.h>

#include <IOKit/IOKitLib.h>
#include <IOKit/IOMessage.h>
#include <IOKit/IOCFPlugIn.h>
#include <IOKit/usb/IOUSBLib.h>


#include "usb_macosx_DeviceImpl.h"
#include "usb_macosx_MacOSX.h"

//================================================================================================
//   Typedefs and Defines
//================================================================================================
//

#define jusbMAX_USB_DEVICE_HANDLES 128 //surely no one will try to use > 128 MultiTouch devices simultaneously
#define jusbMAX_ENDPOINTS 16

typedef struct JUSBInterfaceHandleData {
    IOUSBInterfaceInterface183 * *interface;
    UInt8 num_pipes;
    UInt8  openedExclusive;
} JUSBInterfaceHandleData;

typedef struct JUSBDeviceHandleData {
    io_object_t         notification;
    IOUSBDeviceInterface187 *   *device;
    JUSBInterfaceHandleData *interfaceHandles;
    UInt8 endpoint2pipe_table[jusbMAX_ENDPOINTS];
    UInt8 endpoint2interface_table[jusbMAX_ENDPOINTS];
    //CFStringRef         deviceName;
    UInt32          locationID;
    UInt16          vendorID;
    UInt16          productID;
    UInt8  openedExclusive;
    UInt16 num_interfaces;
    char*         deviceNameAndLoc;
} JUSBDeviceHandleData;

//================================================================================================
//   Globals
//================================================================================================
//
static IONotificationPortRef    gNotifyPort = NULL;
static io_iterator_t        gAddedIter = 0;
static CFRunLoopRef     gRunLoop = NULL;

static JUSBDeviceHandleData jusbDeviceHandles[jusbMAX_USB_DEVICE_HANDLES];
static JavaVM *jvm = NULL;
static jclass hostclass = NULL;


void releasePrivateData(JUSBDeviceHandleData *privateDataRef) {
    IOReturn kr;
    /*// Dump our private data to stderr just to see what it looks like.
    CFShow(privateDataRef->deviceName);

    // Free the data we're no longer using now that the device is going away
    CFRelease(privateDataRef->deviceName);*/


    //this was allocated with malloc, so don't use CFRelease?
    if (privateDataRef->deviceNameAndLoc != NULL) {
        free(privateDataRef->deviceNameAndLoc);
    }

    if ( privateDataRef->device ) {
        if (privateDataRef->num_interfaces > 0) {
            int i;
            for (i = 0; i < privateDataRef->num_interfaces; i++) {
                JUSBInterfaceHandleData* interfaceHandle = &privateDataRef->interfaceHandles[i];
                if (interfaceHandle->interface != NULL) {
                    if (interfaceHandle->openedExclusive) {
                        kr = (*interfaceHandle->interface)->USBInterfaceClose(interfaceHandle->interface);
                    }
                    kr = (*interfaceHandle->interface)->Release(interfaceHandle->interface);
                }
            }
            free(privateDataRef->interfaceHandles);
            privateDataRef->interfaceHandles = NULL;
            privateDataRef->num_interfaces = 0;
        }
        kr = (*privateDataRef->device)->Release(privateDataRef->device);
    }
    if ( privateDataRef->notification ) {
        kr = IOObjectRelease(privateDataRef->notification);
    }
    //free(privateDataRef);
    bzero(privateDataRef, sizeof(JUSBDeviceHandleData));
}

//================================================================================================
//
//	FindInterfaces
//
//	Since JUSB wants to refer to interfaces by number, but IOKit only lets us search through
//  them with an iterator, we need to create an indexable table of interface handles.
//
//================================================================================================
//

IOReturn FindInterfaces(JUSBDeviceHandleData *privateDataRef) {
    IOReturn kernResult;
    IOUSBFindInterfaceRequest request;
    io_iterator_t iterator;
    io_service_t usbInterface;
    IOCFPlugInInterface **plugInInterface = NULL;
    IOUSBInterfaceInterface183 **interface = NULL;
    IOUSBDeviceInterface187 **device = privateDataRef->device;
    HRESULT result;
    SInt32 score;
/*     UInt8 interfaceClass;
    UInt8 interfaceSubClass;
    UInt8 interfaceNumEndpoints;
    int pipeRef;
#ifndef USE_ASYNC_IO
    UInt32 numBytesRead;
#else
    CFRunLoopSourceRef runLoopSource;
#endif
*/
    UInt8 config_num = 0;
    UInt8 interface_num = 0;
    IOUSBConfigurationDescriptorPtr config_descriptor;

    if (privateDataRef->interfaceHandles != NULL) {
        free(privateDataRef->interfaceHandles);
        privateDataRef->interfaceHandles = NULL;
        privateDataRef->num_interfaces = 0;
    }
    kernResult = (*device)->GetConfiguration(device,&config_num);
    if (kernResult != kIOReturnSuccess) {
        //if(usb_macosx_MacOSX_debug)
        printf("-- jusbMacOSX --: ERROR: GetConfiguration() result=%x\n", kernResult);
        return kernResult;
    }
    kernResult = (*device)->GetConfigurationDescriptorPtr(device,config_num-1,&config_descriptor);
    if (kernResult != kIOReturnSuccess) {
        //if(usb_macosx_MacOSX_debug)
        printf("-- jusbMacOSX --: ERROR:  GetConfigurationDescriptorPtr() result=%x\n", kernResult);
        return kernResult;
    }
/*    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: in findInterfaces(config=%d)\n", config_num-1);*/
    privateDataRef->num_interfaces = config_descriptor->bNumInterfaces;
/*    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: in findInterfaces(num_interfaces=%d,length=%d,type=%x,total=%d,bConfig=%x,power=%x)\n",
               privateDataRef->num_interfaces,config_descriptor->bLength,config_descriptor->bDescriptorType,
               config_descriptor->wTotalLength,config_descriptor->bConfigurationValue,config_descriptor->MaxPower);*/
    privateDataRef->interfaceHandles = (JUSBInterfaceHandleData*)
        malloc(privateDataRef->num_interfaces*sizeof(JUSBInterfaceHandleData));
    bzero(privateDataRef->interfaceHandles,privateDataRef->num_interfaces*sizeof(JUSBInterfaceHandleData));

    //Placing the constant kIOUSBFindInterfaceDontCare into the following
    //fields of the IOUSBFindInterfaceRequest structure will allow you
    //to find all the interfaces
    request.bInterfaceClass = kIOUSBFindInterfaceDontCare;
    request.bInterfaceSubClass = kIOUSBFindInterfaceDontCare;
    request.bInterfaceProtocol = kIOUSBFindInterfaceDontCare;
    request.bAlternateSetting = kIOUSBFindInterfaceDontCare;
    //Get an iterator for the interfaces on the device
    kernResult = (*device)->CreateInterfaceIterator(device,
                                                    &request, &iterator);
    if (kernResult == kIOReturnNoDevice) {
        return kernResult;
    }
    while (usbInterface = IOIteratorNext(iterator)) {
        //Create an intermediate plug-in
        kernResult = IOCreatePlugInInterfaceForService(usbInterface,
                                                       kIOUSBInterfaceUserClientTypeID,
                                                       kIOCFPlugInInterfaceID,
                                                       &plugInInterface, &score);
        //No longer need the usbInterface object after getting the plug-in
        kernResult = IOObjectRelease(usbInterface);
        if ((kernResult != kIOReturnSuccess) || !plugInInterface) {
            printf("Unable to create a plug-in (%08x)\n", kernResult);
            break;
        }
        //Now create the device interface for the interface
        result = (*plugInInterface)->QueryInterface(plugInInterface,
                                                    CFUUIDGetUUIDBytes(kIOUSBInterfaceInterfaceID183),
                                                    (LPVOID) &interface);
        //No longer need the intermediate plug-in
        (*plugInInterface)->Release(plugInInterface);
        if (result || !interface) {
            printf("Couldn't create a device interface for the interface (%08x)\n", (int) result);
            break;
        }
        result = (*interface)->GetInterfaceNumber(interface,&interface_num);
        if (result != kIOReturnSuccess) {
            printf("Couldn't create a device interface for the interface (%08x)\n", (int) result);
            break;
        }
        if(usb_macosx_MacOSX_trace) {
            UInt8 num_endpoints = 0;
            result = (*interface)->GetNumEndpoints(interface,&num_endpoints);
            printf("-- jusbMacOSX --: findInterfaces(config=%d) registering interface(%d) of %d, handle=0x%x with %d endpoints\n",config_num-1,interface_num,privateDataRef->num_interfaces,interface,num_endpoints);
        }
        privateDataRef->interfaceHandles[interface_num].interface = interface;

    }
    return kernResult;
}

IOReturn RegisterEndpoints(JUSBDeviceHandleData *driverHandle, JUSBInterfaceHandleData *interfaceHandle,
                           int interface_num) {
    IOReturn kernResult = kIOReturnSuccess;
    UInt8 interfaceNumEndpoints;
    int pipeRef;

    //Get the number of endpoints associated with this interface
    kernResult = (*interfaceHandle->interface)->GetNumEndpoints(interfaceHandle->interface,
                                                                &interfaceNumEndpoints);
    if (kernResult != kIOReturnSuccess) {
        printf("Unable to get number of endpoints (%08x)\n", kernResult);
        (*interfaceHandle->interface)->USBInterfaceClose(interfaceHandle->interface);
        (*interfaceHandle->interface)->Release(interfaceHandle->interface);
        interfaceHandle->interface = NULL;
        interfaceHandle->openedExclusive = false;
        return kernResult;
    }
    //printf("Interface has %d endpoints\n", interfaceNumEndpoints);
    interfaceHandle->num_pipes = interfaceNumEndpoints;
    //Access each pipe in turn, starting with the pipe at index 1
    //The pipe at index 0 is the Default Control Pipe and should be
    //accessed using (*usbDevice)->DeviceRequest() instead
    for (pipeRef = 1; pipeRef <= interfaceNumEndpoints; pipeRef++) {
        UInt8 direction;
        UInt8 ep_address;
        UInt8 transferType;
        UInt16 maxPacketSize;
        UInt8 interval;
        kernResult = (*interfaceHandle->interface)->GetPipeProperties(interfaceHandle->interface,
                                                                      pipeRef, &direction,
                                                                      &ep_address, &transferType,
                                                                      &maxPacketSize, &interval);
        if (kernResult != kIOReturnSuccess) {
            printf("Unable to get number of endpoints (%08x)\n", kernResult);
            (void) (*interfaceHandle->interface)->USBInterfaceClose(interfaceHandle->interface);
            (void) (*interfaceHandle->interface)->Release(interfaceHandle->interface);
            interfaceHandle->interface = NULL;
            interfaceHandle->openedExclusive = false;
            return kernResult;
        }
        if (ep_address < jusbMAX_ENDPOINTS) {
            driverHandle->endpoint2pipe_table[ep_address] = pipeRef;
            driverHandle->endpoint2interface_table[ep_address] = interface_num;
            if (usb_macosx_MacOSX_trace) {
                printf("-- jusbMacOSX --: claimInterface(%d) registered pipe %d of %d, dir=%x,ep_address=0x%x, size%d rf intfHandle=0x%x\n",
                       interface_num,pipeRef,interfaceNumEndpoints,direction,
                       ep_address,maxPacketSize,interfaceHandle->interface);
            }
        } else {
            if (usb_macosx_MacOSX_trace) {
                printf("-- jusbMacOSX --: claimInterface(%d) ep_address %d out of range\n",interface_num,pipeRef);
            }
        }
    }
    return kernResult;
}

JUSBInterfaceHandleData *lookupInterfaceFromEndpoint(int driverhandle, int ep_address, UInt8 *pipe_ref) {
    int endpoint = ep_address & 0xF;
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        JUSBDeviceHandleData *driverHandle = &jusbDeviceHandles[driverhandle];
        if (driverHandle->endpoint2pipe_table[endpoint] != 0
            && driverHandle->endpoint2interface_table[endpoint] < driverHandle->num_interfaces
            && driverHandle->interfaceHandles != NULL) {
            int interface_num = driverHandle->endpoint2interface_table[endpoint];
            /*if (usb_macosx_MacOSX_trace) {
            printf("-- jusbMacOSX --: lookupInterfaceFromEndpoint() intf=%d\n",interface_num);
            }*/
            JUSBInterfaceHandleData *interfaceHandle = &driverHandle->interfaceHandles[interface_num];
            if (interfaceHandle->interface != NULL) {
                *pipe_ref = driverHandle->endpoint2pipe_table[endpoint];
                return interfaceHandle;
            }
        }
    }
    return NULL;
}



//================================================================================================
//
//	DeviceNotification
//
//	This routine will get called whenever any kIOGeneralInterest notification happens.  We are
//	interested in the kIOMessageServiceIsTerminated message so that's what we look for.  Other
//	messages are defined in IOMessage.h.
//
//================================================================================================
//
void DeviceNotification( void *refCon,
                         io_service_t service,
                         natural_t messageType,
                         void *messageArgument ) {
    kern_return_t   kr;
    JUSBDeviceHandleData   *privateDataRef = (JUSBDeviceHandleData *) refCon;
    JNIEnv *env;
    (*jvm)->AttachCurrentThread(jvm,(void **)&env,NULL);
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: in DeviceNotification() with env=%d.\n", env);

    if ( messageType == kIOMessageServiceIsTerminated ) {
        jmethodID IDmethodRemove = (*env)->GetStaticMethodID(env,hostclass,"existingDeviceRemoved","(IIILjava/lang/String;)V");
        jstring nameAndLocString = (*env)->NewStringUTF(env,privateDataRef->deviceNameAndLoc);
        UInt16 vendorID = privateDataRef->vendorID;
        UInt16 productID = privateDataRef->productID;
        UInt16 locationID = privateDataRef->locationID;
        //release the device now so that any late Java Control Msgs during rmBus/killBus are ignored
        releasePrivateData(privateDataRef);
        if(IDmethodRemove != 0) {
        //printf("-- jusbMacOSX --: Method ID's:\n\tAdd: %d\n",IDmethodAdd);
        (*env)->CallStaticVoidMethod(env,hostclass,IDmethodRemove,
                                    vendorID,productID,locationID,nameAndLocString);
        if(usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Device 0x%08x removed.\n", service);
        } else {
            printf("-- jusbMacOSX --: ERROR existingDeviceRemoved Method ID null, hostclass %d, env %d\n",hostclass,env);
        }
        (*env)->DeleteLocalRef(env,nameAndLocString);
    }
}

void freeJusbDeviceHandles(void) {
    int h;
    for (h = 0; h < jusbMAX_USB_DEVICE_HANDLES; h++) {
        releasePrivateData(&jusbDeviceHandles[h]);
    }
}


int allocateJusbDeviceHandle(void) {
    static int last_my_usb_device_handle = jusbMAX_USB_DEVICE_HANDLES-1;
    int next_handle = last_my_usb_device_handle;
    do {
        next_handle++;
        if (next_handle >= jusbMAX_USB_DEVICE_HANDLES) {
            next_handle = 1; //wrap around
        }
        if (jusbDeviceHandles[next_handle].deviceNameAndLoc == NULL) {
            last_my_usb_device_handle = next_handle;
            return next_handle;
        }

    } while (next_handle != last_my_usb_device_handle);
    //no free handles found, return default handle (0)
    return 0;
}
//================================================================================================
//
//	DeviceAdded
//
//	This routine is the callback for our IOServiceAddMatchingNotification.  When we get called
//	we will look at all the devices that were added and we will:
//
//	1.  Create some private data to relate to each device (in this case we use the service's name
//	    and the location ID of the device
//	2.  Submit an IOServiceAddInterestNotification of type kIOGeneralInterest for this device,
//	    using the refCon field to store a pointer to our private data.  When we get called with
//	    this interest notification, we can grab the refCon and access our private data.
//
//================================================================================================
//
void DeviceAdded(void *refCon, io_iterator_t iterator) {
    kern_return_t       kr;
    io_service_t        usbDevice;
    IOCFPlugInInterface     **plugInInterface=NULL;
    SInt32          score;
    HRESULT             res;
    JNIEnv *env;
    (*jvm)->AttachCurrentThread(jvm,(void **)&env,NULL);

    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: in DeviceAdded() with env=%d.\n", env);

    while ( usbDevice = IOIteratorNext(iterator) ) {
        io_name_t       deviceName;
        UInt8 deviceClass;
        //CFStringRef     deviceNameAsCFString;
        JUSBDeviceHandleData       *privateDataRef = NULL;
        jmethodID IDmethodAdd;
        jstring nameAndLocString;
        int my_handle_num=0;

        if(usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Device 0x%08x added.\n", usbDevice);

        // Add some app-specific information about this device.
        // Create a buffer to hold the data.

        my_handle_num = allocateJusbDeviceHandle();
        if (my_handle_num <= 0) {
            //if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: jUSB library is out of USB device handles!\n");
            continue;
        }
        privateDataRef = &jusbDeviceHandles[my_handle_num];
        bzero(privateDataRef, sizeof(JUSBDeviceHandleData));

        // Get the USB device's name.
        kr = IORegistryEntryGetName(usbDevice, deviceName);
        if ( KERN_SUCCESS != kr ) {
            deviceName[0] = '\0';
        }

        /*deviceNameAsCFString = CFStringCreateWithCString(kCFAllocatorDefault, deviceName,
                                                         kCFStringEncodingASCII);

        // Dump our data to stderr just to see what it looks like.
        CFShow(deviceNameAsCFString);

        // Save the device's name to our private data.
        privateDataRef->deviceName = deviceNameAsCFString;*/

        // Now, get the locationID of this device. In order to do this, we need to create an IOUSBDeviceInterface
        // for our device. This will create the necessary connections between our userland application and the
        // kernel object for the USB Device.
        kr = IOCreatePlugInInterfaceForService(usbDevice, kIOUSBDeviceUserClientTypeID, kIOCFPlugInInterfaceID,
                                               &plugInInterface, &score);

        if ( (kIOReturnSuccess != kr) || !plugInInterface ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: unable to create a plugin (%08x) for %s\n", kr, deviceName);
            continue;
        }

        // Use the plugin interface to retrieve the device interface.
        res = (*plugInInterface)->QueryInterface(plugInInterface, CFUUIDGetUUIDBytes(kIOUSBDeviceInterfaceID187),
                                                 (LPVOID) &privateDataRef->device);

        // Now done with the plugin interface.
        (*plugInInterface)->Release(plugInInterface);

        if ( res || !privateDataRef->device ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: couldn't create a device interface (%08x) for %s\n", (int) res,deviceName);
            continue;
        }
        kr = (*privateDataRef->device)->GetDeviceClass(privateDataRef->device, &deviceClass);
        if ( KERN_SUCCESS != kr || deviceClass == 0x09 || deviceClass == 0xff) {
            //we don't want jusb talking to Hubs (deviceClass 0x09) 'cause that crashes the bus?
            //we don't want jusb talking to (deviceClass 0xff) as this throws a control read error in IOkit on OSX
            //usb.macosx.USBException: control read error --  [0x1ffffd13]  [0x1ffffd13]
            releasePrivateData(privateDataRef);
            continue;
        }


        // Now that we have the IOUSBDeviceInterface, we can call the routines in IOUSBLib.h.
        // In this case, fetch the locationID. The locationID uniquely identifies the device
        // and will remain the same, even across reboots, so long as the bus topology doesn't change.

        kr = (*privateDataRef->device)->GetLocationID(privateDataRef->device, &privateDataRef->locationID);
        if ( KERN_SUCCESS != kr ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: GetLocationID returned err %08x for %s\n", kr, deviceName);
            releasePrivateData(privateDataRef);
            continue;
        }
        kr = (*privateDataRef->device)->GetDeviceVendor(privateDataRef->device, &privateDataRef->vendorID);
        if ( KERN_SUCCESS != kr ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: GetVendorID returned err %08x for %s\n", kr, deviceName);
            releasePrivateData(privateDataRef);
            continue;
        }
        kr = (*privateDataRef->device)->GetDeviceProduct(privateDataRef->device, &privateDataRef->productID);
        if ( KERN_SUCCESS != kr ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: GetProductID returned err %08x for %s\n", kr, deviceName);
            releasePrivateData(privateDataRef);
            continue;
        }

        privateDataRef->deviceNameAndLoc = malloc(strlen(deviceName) + 60);
        sprintf(privateDataRef->deviceNameAndLoc,"@0x%08lx  %s  VID=0x%x PID=0x%x\n",
                privateDataRef->locationID,
                deviceName,
                privateDataRef->vendorID,
                privateDataRef->productID);
        if(usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Calling newFWDevice for: %s\n", privateDataRef->deviceNameAndLoc);

        IDmethodAdd=(*env)->GetStaticMethodID(env,hostclass,"newDeviceAvailable","(IIILjava/lang/String;)V");
        nameAndLocString = (*env)->NewStringUTF(env,privateDataRef->deviceNameAndLoc);
        if(IDmethodAdd != 0) {
            if(usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: Calling newDeviceAvailable() Method ID: %d\n",IDmethodAdd);
        (*env)->CallStaticVoidMethod(env,hostclass,IDmethodAdd,
                                  privateDataRef->vendorID,privateDataRef->productID,
                                  privateDataRef->locationID,nameAndLocString);
            if(usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: Back from newDeviceAvailable()  Method ID: %d\n",IDmethodAdd);
        } else {
            printf("-- jusbMacOSX --: ERROR newDeviceAvailable()  Method ID null, hostclass: %d, env: %d\n",hostclass, env);
        }

        (*env)->DeleteLocalRef(env,nameAndLocString);

        // Register for an interest notification of this device being removed. Use a reference to our
        // private data as the refCon which will be passed to the notification callback.
        kr = IOServiceAddInterestNotification( gNotifyPort,         // notifyPort
                                               usbDevice,           // service
                                               kIOGeneralInterest,      // interestType
                                               DeviceNotification,      // callback
                                               privateDataRef,          // refCon
                                               &(privateDataRef->notification)  // notification
                                             );

        if ( KERN_SUCCESS != kr ) {
            if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
                printf("-- jusbMacOSX --: IOServiceAddInterestNotification returned err %08x\n", kr);
        }

        // Done with this USB device; release the reference added by IOIteratorNext
        kr = IOObjectRelease(usbDevice);
    }
}



//================================================================================================
//
//	SignalHandler
//
//	This routine will get called when we interrupt the program (usually with a Ctrl-C from the
//	command line).  We clean up so that we don't leak.
//
//================================================================================================
//
void CleanupNotifiers() {
    // Clean up here
    if(gNotifyPort) {
        IONotificationPortDestroy(gNotifyPort);
        gNotifyPort = 0;
    }
    if ( gAddedIter ) {
        IOObjectRelease(gAddedIter);
        gAddedIter = 0;
    }

    freeJusbDeviceHandles();
}

void SignalHandler(int sigraised) {
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: Interrupted\n");
    CleanupNotifiers();
    exit(0);
}

JNIEXPORT void JNICALL Java_usb_macosx_MacOSX_haltDeviceScan
(JNIEnv *env, jclass theclass) {
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: closing down native device scan\n");
    CleanupNotifiers();
    if (hostclass != NULL)
        (*env)->DeleteWeakGlobalRef(env,hostclass);
}



JNIEXPORT jlong JNICALL Java_usb_macosx_MacOSX_scanForDevices
(JNIEnv * env, jclass theclass, jint match_vendorID, jint match_productID) {
    mach_port_t         masterPort;
    CFMutableDictionaryRef  matchingDict;
    CFRunLoopSourceRef      runLoopSource;
    CFNumberRef         numberRef;
    kern_return_t       kr;
    long            usbVendor = match_vendorID;
    long            usbProduct = match_productID;
    sig_t           oldHandler;
    int jvm_return;

    jvm_return = (*env)->GetJavaVM(env,&jvm);
    hostclass = (*env)->NewWeakGlobalRef(env,theclass);
    if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Device enumeration started with hostclass=%ld\n", hostclass);

    bzero(jusbDeviceHandles, jusbMAX_USB_DEVICE_HANDLES*sizeof(JUSBDeviceHandleData));

    // Set up a signal handler so we can clean up when we're interrupted from the command line
    // Otherwise we stay in our run loop forever.
/*    oldHandler = signal(SIGINT, SignalHandler);
    if ( oldHandler == SIG_ERR ) {
        if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Could not establish new signal handler");
    }*/

    // First create a master_port for my task
    kr = IOMasterPort(MACH_PORT_NULL, &masterPort);
    if ( kr || !masterPort ) {
        if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: ERR: Couldn't create a master IOKit port(%08x)\n", kr);
        return -1;
    }

    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: Looking for devices matching vendor ID=%ld and product ID=%ld\n", usbVendor, usbProduct);

    // Set up the matching criteria for the devices we're interested in. The matching criteria needs to follow
    // the same rules as kernel drivers: mainly it needs to follow the USB Common Class Specification, pp. 6-7.
    // See also <http://developer.apple.com/qa/qa2001/qa1076.html>.
    // One exception is that you can use the matching dictionary "as is", i.e. without adding any matching
    // criteria to it and it will match every IOUSBDevice in the system. IOServiceAddMatchingNotification will
    // consume this dictionary reference, so there is no need to release it later on.

    matchingDict = IOServiceMatching(kIOUSBDeviceClassName);    // Interested in instances of class
                                                                // IOUSBDevice and its subclasses
    if ( !matchingDict ) {
        if (usb_macosx_MacOSX_debug || usb_macosx_MacOSX_trace)
            printf("-- jusbMacOSX --: Can't create a USB matching dictionary\n");
        mach_port_deallocate(mach_task_self(), masterPort);
        return -1;
    }

    // We are interested in all USB Devices (as opposed to USB interfaces).  The Common Class Specification
    // tells us that we need to specify the idVendor, idProduct, and bcdDevice fields, or, if we're not interested
    // in particular bcdDevices, just the idVendor and idProduct.  Note that if we were trying to match an
    // IOUSBInterface, we would need to set more values in the matching dictionary (e.g. idVendor, idProduct,
    // bInterfaceNumber and bConfigurationValue.
    //

    if (usbVendor >= 0 && usbVendor <= 0xFFFF
        && usbProduct >= 0 && usbProduct <= 0xFFFF) {
        // Create a CFNumber for the idVendor and set the value in the dictionary
        numberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &usbVendor);
        CFDictionarySetValue(matchingDict,
                             CFSTR(kUSBVendorID),
                             numberRef);
        CFRelease(numberRef);

        // Create a CFNumber for the idProduct and set the value in the dictionary
        numberRef = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &usbProduct);
        CFDictionarySetValue(matchingDict,
                             CFSTR(kUSBProductID),
                             numberRef);
        CFRelease(numberRef);

        numberRef = 0;


    }
    // Create a notification port and add its run loop event source to our run loop
    // This is how async notifications get set up.

    gNotifyPort = IONotificationPortCreate(masterPort);
    runLoopSource = IONotificationPortGetRunLoopSource(gNotifyPort);

    gRunLoop = CFRunLoopGetCurrent();
    CFRunLoopAddSource(gRunLoop, runLoopSource, kCFRunLoopDefaultMode);

    // Now set up a notification to be called when a device is first matched by I/O Kit.
    kr = IOServiceAddMatchingNotification(gNotifyPort,          // notifyPort
                                          kIOFirstMatchNotification,    // notificationType
                                          matchingDict,         // matching
                                          DeviceAdded,          // callback
                                          NULL,             // refCon
                                          &gAddedIter           // notification
                                         );

    // Iterate once to get already-present devices and arm the notification
    DeviceAdded(NULL, gAddedIter);

    // Now done with the master_port
    mach_port_deallocate(mach_task_self(), masterPort);
    masterPort = 0;

    // Start the run loop. Now we'll receive notifications.
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: Starting run loop.\n");
    CFRunLoopRun();


    // We should never get here
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: Unexpectedly back from CFRunLoopRun()!\n");
//	printf("-- jusbMacOSX --: Device enumeration ended.\n");
    return 0;
}



/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    openNative
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_openNative
(JNIEnv * env, jclass theclass, jstring drivername) {
    int handle;
    IOReturn retval = kIOReturnNoDevice;
    jlong errstatus;

    const char* windrivername = (*env)->GetStringUTFChars(env,drivername, 0);
    if(usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: in openNative: \n");

    for (handle = 1; handle < jusbMAX_USB_DEVICE_HANDLES; handle++) {
        if (jusbDeviceHandles[handle].deviceNameAndLoc != NULL) {
            if (strcmp(windrivername,jusbDeviceHandles[handle].deviceNameAndLoc) == 0) {
                (*env)->ReleaseStringUTFChars(env,drivername, windrivername);
                return handle;
            }
        }
    }
    (*env)->ReleaseStringUTFChars(env,drivername, windrivername);

    errstatus = (jlong)kIOReturnNoDevice;//no such device
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}


/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    openExclusiveNative
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_openExclusiveNative
(JNIEnv * env, jclass theclass, jint driverhandle) {
    IOReturn retval = kIOReturnNoDevice;
    jlong errstatus;
    if (usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: openExclusiveNative...\n");
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        retval = (*jusbDeviceHandles[driverhandle].device)->USBDeviceOpenSeize(jusbDeviceHandles[driverhandle].device);
        if (retval == kIOReturnSuccess) {
            jusbDeviceHandles[driverhandle].openedExclusive = true;
        }
    }
//	printf("-- jusbMacOSX --: CloseHandle returning %d.\n",retval);
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}


/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    closeNative
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_closeNative
(JNIEnv * env, jclass theclass, jint driverhandle) {
    IOReturn retval = kIOReturnSuccess;
    jlong errstatus;
    if (usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: closeNative...\n");
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL
        && jusbDeviceHandles[driverhandle].openedExclusive) {
        jusbDeviceHandles[driverhandle].openedExclusive = false;
        retval = (*jusbDeviceHandles[driverhandle].device)->USBDeviceClose(jusbDeviceHandles[driverhandle].device);
    }
//	printf("-- jusbMacOSX --: CloseHandle returning %d.\n",retval);
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    claimInterface
 * Signature: (II)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_claimInterface
(JNIEnv *env, jclass theclass, jint driver_handle, jint interface_num) {
    IOReturn kernResult = kIOReturnNoDevice;
    jlong errstatus;
    if (driver_handle > 0 && driver_handle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driver_handle].device != NULL) {
        JUSBDeviceHandleData *driverHandle = &jusbDeviceHandles[driver_handle];
        if (driverHandle->num_interfaces == 0)
            kernResult = FindInterfaces(driverHandle);
        /*        if (usb_macosx_MacOSX_trace) {
            printf("-- jusbMacOSX --: in claimInterface(%d) of %d\n",interface_num,driverHandle->num_interfaces);
        }*/
        if (kernResult == kIOReturnSuccess
            && driverHandle->num_interfaces > interface_num
            && driverHandle->interfaceHandles != NULL) {
            JUSBInterfaceHandleData *interfaceHandle = &driverHandle->interfaceHandles[interface_num];

            //Now open the interface. This will cause the pipes associated with
            //the endpoints in the interface descriptor to be instantiated
            kernResult = (*interfaceHandle->interface)->USBInterfaceOpen(interfaceHandle->interface);
            if (kernResult == kIOReturnSuccess) {
                interfaceHandle->openedExclusive = true;
                if (usb_macosx_MacOSX_trace) {
                    printf("-- jusbMacOSX --: claimed Interface(%d) of %d on drvhandle=%d",
                            interface_num,driverHandle->num_interfaces,driver_handle);
                }
                kernResult = RegisterEndpoints(driverHandle,interfaceHandle,interface_num);
            } else { //
                printf("Unable to claim interface %d (err %08x)\n",interface_num, kernResult);
                (*interfaceHandle->interface)->Release(interfaceHandle->interface);
                interfaceHandle->interface = NULL;
            }
        } else {
            printf("Device does not have interface at index %d (err %08x)\n", interface_num,kernResult);
        }
    }
    errstatus = (jlong)kernResult;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}


/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    releaseInterface
 * Signature: (II)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_releaseInterface
(JNIEnv *env, jclass theclass, jint driverhandle, jint interface_num) {
    jlong errstatus;
    IOReturn retval = kIOReturnNoDevice;

    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        if (jusbDeviceHandles[driverhandle].num_interfaces > interface_num
            && jusbDeviceHandles[driverhandle].interfaceHandles != NULL) {
            JUSBInterfaceHandleData *interfaceHandle = &jusbDeviceHandles[driverhandle].interfaceHandles[interface_num];
            if (interfaceHandle->interface != NULL) {
                retval = (*interfaceHandle->interface)->USBInterfaceClose(interfaceHandle->interface);
                (*interfaceHandle->interface)->Release(interfaceHandle->interface);
            }
            interfaceHandle->openedExclusive = false;
            interfaceHandle->interface = NULL;
        }
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    setInterface
 * Signature: (III)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_setInterface
(JNIEnv *env, jclass theclass, jint driverhandle, jint interface_num, jint alternate_set) {
    IOReturn retval = kIOReturnNoDevice;
    jlong errstatus;
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        JUSBDeviceHandleData *driverHandle = &jusbDeviceHandles[driverhandle];
        if (driverHandle->num_interfaces > interface_num
            && driverHandle->interfaceHandles != NULL
            && driverHandle->interfaceHandles[interface_num].interface != NULL) {
            JUSBInterfaceHandleData *interfaceHandle = &driverHandle->interfaceHandles[interface_num];
            retval = (*interfaceHandle->interface)->SetAlternateInterface(                          						interfaceHandle->interface,(UInt8)alternate_set);
        }
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}



/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    cyclePort
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_cyclePort
(JNIEnv *env, jclass theclass, jint driverhandle) {
    IOReturn retval = kIOReturnNoDevice;
    jlong errstatus;
    if (usb_macosx_MacOSX_trace)
        printf("-- jusbMacOSX --: cyclePort...\n");
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        if (!jusbDeviceHandles[driverhandle].openedExclusive) {
            retval = (*jusbDeviceHandles[driverhandle].device)->USBDeviceOpenSeize(jusbDeviceHandles[driverhandle].device);
            if (retval == kIOReturnSuccess) {
                jusbDeviceHandles[driverhandle].openedExclusive = true;
            } else return -(jlong)retval;
        }
        retval = (*jusbDeviceHandles[driverhandle].device)->USBDeviceReEnumerate(jusbDeviceHandles[driverhandle].device,0);
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    controlMsg
 * Signature: (IBBSS[BIS)J
 */


JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_controlMsg
(JNIEnv *env, jclass theclass, jint driverhandle, jbyte requestType, jbyte request, jshort value, jshort index, jbyteArray buf, jint off, jshort length) {
//	printf("-- jusbMacOSX --: Controlmsg... handle is %d\n",driverhandle);
    IOReturn retval = kIOReturnNoDevice;
    jlong errstatus;
    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        IOUSBDevRequestTO controlRequestTo;
        UInt32 outputLength=(UInt32)length;
    //	printf("-- jusbMacOSX --: Allocating %d byte buffer.\n",outputLength*sizeof(char));

        jboolean isCopy;
        jbyte* inputBuffer=NULL;

        if ( length > 0 ) {
            inputBuffer = (*env)->GetByteArrayElements(env,buf, &isCopy);
            controlRequestTo.pData = (char*)inputBuffer+off;
        } else {
            controlRequestTo.pData = NULL;
        }

        controlRequestTo.bmRequestType = (UInt8)requestType;
        controlRequestTo.bRequest = (UInt8)request;
        //controlRequestTo.wValue = USBToHostWord(value);
        //controlRequestTo.wIndex = USBToHostWord(index);
        //controlRequestTo.wLength = USBToHostWord(length);
        controlRequestTo.wValue = (UInt16)value;
        controlRequestTo.wIndex = (UInt16)index;
        controlRequestTo.wLength = (UInt16)length;
        controlRequestTo.wLenDone = 0;
        controlRequestTo.noDataTimeout = 5000;
        controlRequestTo.completionTimeout = 10000;
        retval = (*jusbDeviceHandles[driverhandle].device)->DeviceRequestTO(jusbDeviceHandles[driverhandle].device,
                                                                                           &controlRequestTo);
        if (usb_macosx_MacOSX_trace && controlRequestTo.bmRequestType == 0x40) {
            printf("-- jusbMacOSX --: Vendor Control Write 0x%x returning 0x%x\n",(UInt16)request,
                   retval);
        }

        if (inputBuffer != NULL) {
            (*env)->ReleaseByteArrayElements(env,buf, inputBuffer, 0);
        }
        if ( retval == kIOReturnSuccess ) {
            return (jlong)controlRequestTo.wLenDone;
        } else if(usb_macosx_MacOSX_debug) {
            printf("-- jusbMacOSX --: ControlMsg returning 0x%08lx.\n",retval);
        }
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    setConfiguration
 * Signature: (II[BII)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_setConfiguration
  (JNIEnv *env, jclass theclass, jint driverhandle, jint config_num) {
      jlong errstatus;
    IOReturn retval = kIOReturnNoDevice;

    if (driverhandle > 0 && driverhandle < jusbMAX_USB_DEVICE_HANDLES
        && jusbDeviceHandles[driverhandle].device != NULL) {
        JUSBDeviceHandleData *driverHandle = &jusbDeviceHandles[driverhandle];

        retval = (*driverHandle->device)->SetConfiguration(driverHandle->device,(UInt8)config_num);
        if (retval == kIOReturnSuccess) {
            //rebuild the handles to new configuration's interfaces?
            retval = FindInterfaces(&jusbDeviceHandles[driverhandle]);
        }
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}


/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    readBulk
 * Signature: (II[BII)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_readBulk
  (JNIEnv *env, jclass theclass, jint driver_handle, jint endpoint,
        jbyteArray buf, jint offset, jint length) {
    IOReturn retval = kIOReturnNoDevice;
    UInt8 pipe_num;
    jlong errstatus;
    JUSBInterfaceHandleData *interfaceHandle = lookupInterfaceFromEndpoint(driver_handle,endpoint,&pipe_num);
        if (usb_macosx_MacOSX_trace) {
            printf("-- jusbMacOSX --: readBulk(ep=%x, pipe_num=%d, length=%d) drvhandle=%d, intfhandle=0x%x\n",endpoint,pipe_num,length,driver_handle,interfaceHandle->interface);
        }
    if (interfaceHandle != NULL) {
        if ( length > 0 ) {
            UInt32 completionTimeout=100000;
            UInt32 noDataTimeout=10000;
            jboolean isCopy;
            jbyte* readBuffer = (*env)->GetByteArrayElements(env,buf, &isCopy);
            UInt32 read_length = length;
            retval = (*interfaceHandle->interface)->
                ReadPipeTO(interfaceHandle->interface,pipe_num,
                            (char*)(readBuffer+offset),&read_length,
                            noDataTimeout,completionTimeout);
            (*env)->ReleaseByteArrayElements(env,buf, readBuffer, 0);
            if ( retval == kIOReturnSuccess ) {
                if (usb_macosx_MacOSX_debug) {
                    printf("-- jusbMacOSX --: readBulk() returning %d bytes\n",read_length);
                }
                return (jlong)read_length;
            } else if(retval == kIOReturnNotResponding) {
                return 0;
        } else if(usb_macosx_MacOSX_trace) {
                printf("-- jusbMacOSX --: ReadPipeTO returning 0x%08lx.\n",retval);
        }
        } else return 0;
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    writeBulk
 * Signature: (II[BII)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_writeBulk
  (JNIEnv *env, jclass theclass, jint driver_handle, jint endpoint,
   jbyteArray buf, jint offset, jint length) {
    IOReturn retval = kIOReturnNoDevice;
      jlong errstatus;
      UInt8 pipe_num;

    JUSBInterfaceHandleData *interfaceHandle = lookupInterfaceFromEndpoint(driver_handle,endpoint,&pipe_num);
    if (usb_macosx_MacOSX_trace) {
        printf("-- jusbMacOSX --: writeBulk(ep=%x, pipe_num=%d, length=%d) drvhandle=%d, intfhandle=0x%x\n",endpoint,pipe_num,length,driver_handle,interfaceHandle->interface);
    }
    if (interfaceHandle != NULL) {
        if ( length > 0 ) {
            UInt32 completionTimeout=100000;
            UInt32 noDataTimeout=10000;
            jboolean isCopy;
            jbyte* writeBuffer = (*env)->GetByteArrayElements(env,buf, &isCopy);
            retval = (*interfaceHandle->interface)->
                WritePipeTO(interfaceHandle->interface,pipe_num,
                            (char*)writeBuffer+offset,length,
                            noDataTimeout,completionTimeout);
            (*env)->ReleaseByteArrayElements(env,buf, writeBuffer, 0);
            if ( retval == kIOReturnSuccess ) {
                return (jlong)length;
            } else if(usb_macosx_MacOSX_debug) {
                printf("-- jusbMacOSX --: WritePipeTO returning 0x%08lx.\n",retval);
            }
        } else return 0;
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    readIntr
 * Signature: (II[BII)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_readIntr
  (JNIEnv *env, jclass theclass, jint driverhandle, jint endpoint,
                   jbyteArray buf, jint offset, jint length) {
    return Java_usb_macosx_DeviceImpl_readBulk(env,theclass,driverhandle,endpoint,buf,offset,length);
}

/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    writeIntr
 * Signature: (II[BII)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_writeIntr
  (JNIEnv *env, jclass theclass, jint driverhandle, jint endpoint,
        jbyteArray buf, jint offset, jint length) {
    return Java_usb_macosx_DeviceImpl_writeBulk(env,theclass,driverhandle,endpoint,buf,offset,length);
}


/*
 * Class:     usb_macosx_DeviceImpl
 * Method:    clearHalt
 * Signature: (IB)I
 */
JNIEXPORT jlong JNICALL Java_usb_macosx_DeviceImpl_clearHalt
  (JNIEnv *env, jclass theclass, jint driverhandle, jbyte endpoint) {
    IOReturn retval = kIOReturnNoDevice;
      jlong errstatus;
    UInt8 pipe_num;
    JUSBInterfaceHandleData *interfaceHandle = lookupInterfaceFromEndpoint(driverhandle,endpoint,&pipe_num);
    if (interfaceHandle != NULL) {
        retval = (*interfaceHandle->interface)->ResetPipe(interfaceHandle->interface,pipe_num);
        if (retval == kIOReturnSuccess) {
            return retval;
        }
    }
    errstatus = (jlong)retval;
    //force errstatus to be negative long (from unsigned int)
    if (errstatus > 0) return -errstatus;
    else return errstatus;
}

