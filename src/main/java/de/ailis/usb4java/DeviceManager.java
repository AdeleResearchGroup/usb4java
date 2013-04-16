/*
 * Copyright (C) 2013 Klaus Reimer <k@ailis.de>
 * See LICENSE.md for licensing information.
 */

package de.ailis.usb4java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.usb.UsbException;
import javax.usb.UsbHub;

import de.ailis.usb4java.descriptors.SimpleUsbDeviceDescriptor;
import de.ailis.usb4java.libusb.Context;
import de.ailis.usb4java.libusb.Device;
import de.ailis.usb4java.libusb.DeviceDescriptor;
import de.ailis.usb4java.libusb.DeviceList;
import de.ailis.usb4java.libusb.LibUsb;
import de.ailis.usb4java.libusb.LibUsbException;

/**
 * Manages the USB devices.
 * 
 * @author Klaus Reimer (k@ailis.de)
 */
final class DeviceManager
{
    /** The scan interval in milliseconds. */
    private static final int DEFAULT_SCAN_INTERVAL = 500;

    /** The logger. */
    private static final Logger LOG = Logger.getLogger(DeviceManager.class
        .getName());

    /** The virtual USB root hub. */
    private final RootHub rootHub;

    /** The libusb context. */
    private final Context context;

    /** If scanner already scanned for devices. */
    private boolean scanned = false;

    /** The currently connected devices. */
    private final Map<DeviceId, AbstractDevice> devices = Collections
        .synchronizedMap(new HashMap<DeviceId, AbstractDevice>());

    /**
     * Constructs a new device manager.
     * 
     * @param rootHub
     *            The root hub. Must not be null.
     * @throws UsbException
     *             When USB initialization fails.
     */
    DeviceManager(final RootHub rootHub) throws UsbException
    {
        if (rootHub == null)
            throw new IllegalArgumentException("rootHub must be set");
        this.rootHub = rootHub;
        this.context = new Context();
        final int result = LibUsb.init(this.context);
        if (result != 0)
            throw new LibUsbException("Unable to initialize libusb", result);
    }

    /**
     * Dispose the USB device manager. This exits the USB context opened by the
     * constructor.
     */
    public void dispose()
    {
        LibUsb.exit(this.context);
    }

    /**
     * Creates a device ID from the specified device.
     * 
     * @param device
     *            The libusb device.
     * @return The device id. Null if device is null or ID could not be build
     *         because an error occured while reading the device descriptor.
     *         Device should be ignored in this case.
     */
    private DeviceId createId(final Device device)
    {
        if (device == null) return null;
        final int busNumber = LibUsb.getBusNumber(device);
        final int addressNumber = LibUsb.getDeviceAddress(device);
        final int portNumber = LibUsb.getPortNumber(device);
        final DeviceDescriptor deviceDescriptor = new DeviceDescriptor();
        final int result = LibUsb.getDeviceDescriptor(device, deviceDescriptor);
        if (result < 0)
        {
            LOG.warning("Unable to get device descriptor for device " +
                addressNumber + " at bus " + busNumber + ": " +
                LibUsb.errorName(result));
            return null;
        }
        return new DeviceId(busNumber, addressNumber, portNumber,
            new SimpleUsbDeviceDescriptor(deviceDescriptor));
    }

    /**
     * Scans the specified ports for removed devices. 
     * 
     * @param ports
     *            The ports to scan for removals.
     */
    private void scanRemovedDevices(final UsbPorts<Port, AbstractDevice> ports)
    {
        for (AbstractDevice device: ports.getAttachedUsbDevices())
        {
            // Scan for removed child devices if current device is a hub
            if (device.isUsbHub()) scanRemovedDevices((Hub) device);
            
            // If device is no longer present then remove it
            if (!this.devices.containsKey(device.getId()))
                ports.disconnectUsbDevice(device);                    
        }
    }
    
    /**
     * Scans the specified ports for new devices. 
     * 
     * @param ports
     *            The ports to scan for new devices.
     * @param hubId
     *            The hub ID. Null if scanned hub is the root hub.
     */
    private void scanNewDevices(final UsbPorts<Port, AbstractDevice> ports,
        final DeviceId hubId)
    {
        for (AbstractDevice device: this.devices.values())
        {
            // Get parent ID from device and reset it to null if we don't
            // know this parent device (This happens on Windows because some
            // devices/hubs can't be fully enumerated.)
            DeviceId parentId = device.getParentId();
            if (!this.devices.containsKey(parentId)) parentId = null;
            
            if (DeviceId.equals(parentId, hubId))
            {
                if (!ports.isUsbDeviceAttached(device))
                {
                    // Connect new devices to the ports of the current hub.
                    ports.connectUsbDevice(device);
                }

                // Scan for removed child devices if current device is a hub
                if (device.isUsbHub()) scanNewDevices((Hub) device,
                    device.getId());
            }
        }
        
    }

    /**
     * Scans the specified hub for changes.
     * 
     * @param hub
     *            The hub to scan.
     */
    public void scan(final UsbHub hub)
    {
        try
        {
            updateDeviceList();
        }
        catch (LibUsbException e)
        {
            throw new ScanException("Unable to scan for USB devices: " + e, e);
        }
        
        if (hub.isRootUsbHub())
        {
            final RootHub rootHub = (RootHub) hub;
            scanRemovedDevices(rootHub);
            scanNewDevices(rootHub, null);
        }
        else
        {
            final Hub nonRootHub = (Hub) hub;
            scanRemovedDevices(nonRootHub);
            scanNewDevices(nonRootHub, nonRootHub.getId());
        }
    }

    /**
     * Updates the device list by adding newly connected devices to it and by
     * removing no longer connected devices.
     * 
     * @throws LibUsbException
     *             When libusb reported an error which we can't ignore during
     *             scan.
     */
    private void updateDeviceList() throws LibUsbException
    {
        final List<DeviceId> current = new ArrayList<DeviceId>();
        
        // Get device list from libusb and abort if it failed
        final DeviceList devices = new DeviceList();
        final int result = LibUsb.getDeviceList(this.context, devices);
        if (result < 0)
            throw new LibUsbException("Unable to get USB device list",
                result);

        try
        {
            // Iterate over all currently connected devices
            for (final Device libUsbDevice: devices)
            {
                // Create device ID. Ignore device if this fails.
                final DeviceId id = createId(libUsbDevice);
                if (id == null) continue;
                
                // Create new device if not already in device list
                try
                {
                    AbstractDevice device = this.devices.get(id);
                    if (device == null)
                    {
                        final Device parent = LibUsb.getParent(libUsbDevice);
                        final DeviceId parentId = createId(parent);
                        final int speed = LibUsb.getDeviceSpeed(libUsbDevice);
                        final boolean isHub = id.getDeviceDescriptor()
                            .bDeviceClass() == LibUsb.CLASS_HUB;
                        if (isHub)
                        {
                            device = new Hub(this, id, parentId,
                                speed, libUsbDevice);
                        }
                        else
                        {
                            device = new NonHub(this, id,
                                parentId, speed, libUsbDevice);
                        }
                       
                        // Add new device to global device list.
                        this.devices.put(id, device);
                    }
                }
                catch (LibUsbException e)
                {
                    // TODO Add some warnings here
                    continue;
                }
                
                // Remember current device as "current"
                current.add(id);            
            }
            
            this.devices.keySet().retainAll(current);
        }
        finally
        {
            LibUsb.freeDeviceList(devices, true);
        }
    }
    
    /**
     * Scans the USB busses for new or removed devices.
     */
    public void scan()
    {
        scan(this.rootHub);
        this.scanned = true;
    }

    /**
     * Returns the libusb device for the specified id. The device must be freed
     * after use.
     * 
     * @param id
     *            The id of the device to return. Must not be null.
     * @return device The libusb device. Never null.
     * @throws DeviceNotFoundException
     *             When the device was not found.
     * @throws LibUsbException
     *             When libusb reported an error while enumerating USB devices.
     */
    public Device getLibUsbDevice(final DeviceId id) throws LibUsbException
    {
        if (id == null) throw new IllegalArgumentException("id must be set");

        final DeviceList devices = new DeviceList();
        final int result = LibUsb.getDeviceList(this.context, devices);
        if (result < 0)
            throw new LibUsbException("Unable to get USB device list",
                result);
        try
        {
            for (Device device: devices)
            {
                if (id.equals(createId(device)))
                {
                    LibUsb.refDevice(device);
                    return device;
                }
            }
        }
        finally
        {
            LibUsb.freeDeviceList(devices, true);
        }

        throw new DeviceNotFoundException(id);
    }

    /**
     * Releases the specified device.
     * 
     * @param device
     *            The device to release. Must not be null.
     */
    public void releaseDevice(final Device device)
    {
        if (device == null)
            throw new IllegalArgumentException("device must be set");
        LibUsb.unrefDevice(device);
    }

    /**
     * Starts scanning in the background.
     */
    public void start()
    {
        final Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    try
                    {
                        Thread.sleep(DEFAULT_SCAN_INTERVAL);
                    }
                    catch (final InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    scan();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Scans for devices but only if this was not already done.
     */
    public void firstScan()
    {
        if (!this.scanned) scan();
    }
}