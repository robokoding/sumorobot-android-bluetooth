package com.robokoding.sumodroid.plugin;

import java.util.UUID;
import java.util.ArrayList;

import android.util.Log;
import android.content.Intent;
import android.os.Environment;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.bluetooth.BluetoothGatt;
import android.content.BroadcastReceiver;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattCharacteristic;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;

/**
 * This class compiles Arduino code and sends it to the Sumorobot.
 */
public class Sumorobot extends CordovaPlugin {
    /* app tag for log messages */
    private static final String TAG = "Sumorobot";
    /* bluetooth stuff */
    private BluetoothGatt mBluetoothGatt;
    private static String incomingData = "";
    private BluetoothAdapter mBluetoothAdapter;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int STATE_DISCONNECTED = 0;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothGattCharacteristic characteristicTX;
    private BluetoothGattCharacteristic characteristicRX;
    private static ArrayList<BluetoothDevice> mBluetoothDevices;
    private static String sumorobotAddress = "98:D3:31:B2:F4:A1";
    private final static UUID HM_RX_TX = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private final static UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (BluetoothGattService gattService : mBluetoothGatt.getServices()) {
                    /* get characteristic when UUID matches RX/TX UUID */
                    characteristicTX = gattService.getCharacteristic(HM_RX_TX);
                    characteristicRX = gattService.getCharacteristic(HM_RX_TX);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dataUpdate(characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            dataUpdate(characteristic);
        }
    };

    private void dataUpdate(final BluetoothGattCharacteristic characteristic) {
        // For all other profiles, writes the data formatted in HEX.
        final byte[] data = characteristic.getValue();
        Log.d(TAG, "data" + characteristic.getValue());

        if (data != null && data.length > 0) {
            // getting cut off when longer, need to push on new line, 0A
            String received = String.format("%s", new String(data));
            /* when the data contains a response message */
            if (incomingData.contains("true")) {
                webView.sendJavascript("app.sumorobotResponse(true)");
                Log.d(TAG, "received bluetooth data: " + incomingData);
                incomingData = "";
            }
            else if (incomingData.contains("false")) {
                webView.sendJavascript("app.sumorobotResponse(false)");
                Log.d(TAG, "received bluetooth data: " + incomingData);
                incomingData = "";
            } else {
                incomingData += received;
            }
        }
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    private boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (sumorobotAddress != null && address.equals(sumorobotAddress) && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                mBluetoothGatt = device.connectGatt(cordova.getActivity(), false, mGattCallback);
                sumorobotAddress = address;
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(cordova.getActivity(), false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        sumorobotAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    private void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    private void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Write to a given char
     * @param characteristic The characteristic to write to
     */
    private void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(characteristic);
    }   
    
    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    private void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        if (HM_RX_TX.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(TAG, "unable to obtain a BluetoothAdapter");
                throw new Exception("failed to obtain bluetooth adapter");
            }

            mBluetoothDevices = new ArrayList<BluetoothDevice>();
            /* initialize bluetooth connection */
            Log.d(TAG, "initializing bluetooth");
            /* when bluetooth is off */
            if (mBluetoothAdapter.isEnabled() == false) {
                /* turn on bluetooth */
                Log.d(TAG, "requesting user to turn on the bluetooth");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                cordova.getActivity().startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
            /* cancel bluetooth discovery */
            mBluetoothAdapter.cancelDiscovery();
        } catch (Exception e) {
            Log.e(TAG, "bluetooth initialization error: " + e.getMessage());
        }
    }

    private void selectBluetoothDevice() {
        Log.d(TAG, "showing bluetooth devices");
        /* clear previously found devices */
        mBluetoothDevices.clear();
        /* add bonded devices */
        mBluetoothDevices.addAll(mBluetoothAdapter.getBondedDevices());
        int index = 0;
        String[] bluetoothDeviceNames = new String[mBluetoothDevices.size()];
        for (BluetoothDevice device : mBluetoothDevices) {
            bluetoothDeviceNames[index++] = device.getName();
        }
        /* show bluetooth devices for selection */
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
        alertDialog.setCancelable(true);
        alertDialog.setTitle("Please select your sumorobot");
        alertDialog.setItems(bluetoothDeviceNames, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int selectedIndex) {
                sumorobotAddress = mBluetoothDevices.get(selectedIndex).getAddress();
                /* make sure it's disconnected */
                disconnect();
                /* connect to the selected sumorobot */
                connect(sumorobotAddress);
                dialog.dismiss();
            }
        });
        alertDialog.create();
        alertDialog.show();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("sendCommands")) {
            final String commands = args.getString(0);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        /* send the commands to the Sumorobot */
                        if (mConnectionState == STATE_CONNECTED) {
                            Log.d(TAG, "sending commands: " + commands);
                            int start = 0;
                            while (true) {
                                String packet = "";
                                if (commands.length() >= start + 20)
                                    packet = commands.substring(start, start + 20);
                                else
                                    packet = commands.substring(start, commands.length());
                                characteristicTX.setValue(packet);
                                writeCharacteristic(characteristicTX);
                                start += 20;
                                if (start >= commands.length())
                                    break;
                                /* a small delay before sending next packet */
                                Thread.sleep(1);
                            }
                            setCharacteristicNotification(characteristicRX, true);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "sending commands error: " + e.getMessage());
                    }
                }
            }).start();
            callbackContext.success();
            return true;
        } else if (action.equals("selectSumorobot")) {
            /* show dialog to select a bluetooth devices */
            selectBluetoothDevice();
            callbackContext.success();
            return true;
        }
        callbackContext.error("unknown action: " + action);
        return false;
    }
}