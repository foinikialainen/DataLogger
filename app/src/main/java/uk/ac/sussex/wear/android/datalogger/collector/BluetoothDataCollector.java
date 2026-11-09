/*
 * Copyright (c) 2019. Mathias Ciliberto, Francisco Javier Ordoñez Morales,
 * Hristijan Gjoreski, Daniel Roggen, Clara Wurm
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
// (c) 2016 youten, http://greety.sakura.ne.jp/redo/

package uk.ac.sussex.wear.android.datalogger.collector;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import uk.ac.sussex.wear.android.datalogger.collector.BTHelper.DeviceAdapter;
import uk.ac.sussex.wear.android.datalogger.collector.BTHelper.ScannedDevice;
import uk.ac.sussex.wear.android.datalogger.log.CustomLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// child class for collecting Bluetooth data
// functionality from iBeaconDetector https://github.com/youten/iBeaconDetector

/*
* Created by wurmc
* */

public class BluetoothDataCollector extends AbstractDataCollector {

    private static final String TAG = BluetoothDataCollector.class.getSimpleName();

    private CustomLogger logger = null;

    private int mSamplingPeriodUs;

    private Context mContext;

    // Timer to manage specific sampling rates
    private Handler mTimerHandler = null;
    private Runnable mTimerRunnable = null;

    // Receiver class for monitoring changes in BT states
    private BluetoothInfoReceiver mBTInfoReceiver;

    //needed Bluetooth variables
    private BluetoothAdapter mBTAdapter = null;
    private boolean mIsScanning;
    private DeviceAdapter mDeviceAdapter;
    private BluetoothLeScanner mBluetoothLeScanner = null;
    private ScanCallback mScanCallback = null;

    public BluetoothDataCollector(Context context, String sessionName, String sensorName, int samplingPeriodUs, long nanosOffset, int logFileMaxSize) {

        mSensorName = sensorName;
        String path = sessionName + File.separator + mSensorName + "_" + sessionName;

        logger = new CustomLogger(context, path, sessionName, mSensorName, "txt", false, mNanosOffset, logFileMaxSize);

        mSamplingPeriodUs = samplingPeriodUs;
        mContext = context;
        // Offset to match timestamps both in master and slaves devices
        mNanosOffset = nanosOffset;
        // call init
        init();
        if (mSamplingPeriodUs > 0) {
            mTimerHandler = new Handler(Looper.getMainLooper());
            mTimerRunnable = new Runnable() {
                // still problems here !!! run() seems to not work properly. Log doesn't get called
                // no BT txt files are written
                @Override
                public void run() {
                    logBluetoothInfo(mDeviceAdapter.getScanList());
                    int millis = 1000 / mSamplingPeriodUs;
                    mTimerHandler.postDelayed(this, millis);
                }
            };
        } else {
            mBTInfoReceiver = new BluetoothInfoReceiver();
        }

    }

    private void init(){
        // initialise mBTAdapter
        BluetoothManager bluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBTAdapter = (bluetoothManager == null) ? null : bluetoothManager.getAdapter();
        if (mBTAdapter == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            return;
        }

        mDeviceAdapter = new DeviceAdapter(new ArrayList<ScannedDevice>());

        // ScanCallback replaces the deprecated BluetoothAdapter.LeScanCallback (API 21+)
        mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() != null) {
                    byte[] scanRecord = (result.getScanRecord() == null) ? null : result.getScanRecord().getBytes();
                    mDeviceAdapter.updateDevice(result.getDevice(), result.getRssi(), scanRecord);
                }
            }
        };
    }

    private void logBluetoothInfo(List<ScannedDevice> scanList){
        // System local time in millis
        long currentMillis = (new Date()).getTime();

        // System nanoseconds since boot, including time spent in sleep.
        long nanoTime = SystemClock.elapsedRealtimeNanos() + mNanosOffset;

        String message = String.format("%s", currentMillis) + ";"
                + String.format("%s", nanoTime) + ";"
                + String.format("%s", mNanosOffset) + ";"
                + scanList.size();

        for (ScannedDevice scan : scanList){
            // Get BLE Beacon Device Address
            String address = scan.getDevice().getAddress();
            // Get BLE Beacon RSSI
            int rssi = scan.getRssi();
            // Get BLE Beacon Hex Info out of scanRecord
            String scanRecord = scan.getScanRecordHexString();

            message += ";"
                    + address + ";"
                    + rssi + ";"
                    + scanRecord;
        }
        //message += "Hello";
        logger.log(message);
        logger.log(System.lineSeparator());
    }

    private class BluetoothInfoReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            logBluetoothInfo(mDeviceAdapter.getScanList());
        }
    }

    @Override
    public void start() {
        Log.i(TAG, "start:: Starting listener for sensor: " + getSensorName());

        // if query from iBeaconDetector
        if ((mBTAdapter != null) && (!mIsScanning)) {
            mBluetoothLeScanner = mBTAdapter.getBluetoothLeScanner();
            if (mBluetoothLeScanner != null) {
                mBluetoothLeScanner.startScan(mScanCallback);
                mIsScanning = true;
            }
        }

        if (mTimerHandler != null && mTimerRunnable != null) {
            mTimerHandler.postDelayed(mTimerRunnable, 0);
        }

        logger.start();
    }

    @Override
    public void stop() {
        Log.i(TAG,"stop:: Stopping listener for sensor " + getSensorName());

        if (mBluetoothLeScanner != null && mIsScanning) {
            mBluetoothLeScanner.stopScan(mScanCallback);
        }
        mIsScanning = false;

        if (mTimerHandler != null && mTimerRunnable != null) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
        }

        logger.stop();
    }

    @Override
    public void haltAndRestartLogging() {
        logger.stop();
        logger.resetByteCounter();
        logger.start();
    }

    @Override
    public void updateNanosOffset(long nanosOffset) {
        mNanosOffset = nanosOffset;
    }

}
