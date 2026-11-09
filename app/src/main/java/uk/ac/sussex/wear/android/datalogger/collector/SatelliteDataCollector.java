/*
 * Copyright (c) 2017. Mathias Ciliberto, Francisco Javier Ordoñez Morales,
 * Hristijan Gjoreski, Daniel Roggen
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

package uk.ac.sussex.wear.android.datalogger.collector;

import android.content.Context;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Date;

import androidx.core.content.ContextCompat;

import uk.ac.sussex.wear.android.datalogger.log.CustomLogger;

// child class for collecting satellite data (GNSS)

public class SatelliteDataCollector extends AbstractDataCollector {

    private static final String TAG = SatelliteDataCollector.class.getSimpleName();

    private CustomLogger logger = null;

    // The location manager reference
    private LocationManager mLocationManager = null;

    private Context mContext = null;

    // Listener for monitoring changes in GNSS status (API 24+)
    private GnssStatusCallback mGnssStatusCallback = null;

    // Listener for monitoring changes in gps status (legacy, API < 24)
    private GpsStatusListener mGpsStatusListener = null;

    // Latest GNSS status received from the callback
    private volatile GnssStatus mLastGnssStatus = null;


    public SatelliteDataCollector(Context context, String sessionName, String sensorName, long nanosOffset, int logFileMaxSize){

        mContext = context;
        mSensorName = sensorName;
        String path = sessionName + File.separator + mSensorName + "_" + sessionName;

        logger = new CustomLogger(context, path, sessionName, mSensorName, "txt", false, mNanosOffset, logFileMaxSize);

        mLocationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

        // Offset to match timestamps both in master and slaves devices
        mNanosOffset = nanosOffset;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mGnssStatusCallback = new GnssStatusCallback();
        } else {
            mGpsStatusListener = new GpsStatusListener();
        }

    }

    private String buildHeaderMessage(){
        // System nanoseconds since boot, including time spent in sleep.
        long nanoTime = SystemClock.elapsedRealtimeNanos() + mNanosOffset;

        // System local time in millis
        long currentMillis = (new Date()).getTime();

        return String.format("%s", currentMillis) + ";"
                + String.format("%s", nanoTime) + ";"
                + String.format("%s", mNanosOffset);
    }

    // Logs the satellites of the latest GNSS status (API 24+)
    private void logGnssStatus(GnssStatus gnssStatus){
        if (gnssStatus == null) return;

        int satCounter = gnssStatus.getSatelliteCount();

        String message = buildHeaderMessage();

        for (int i = 0; i < satCounter; i++) {
            // Satellite identifier (SVID). For GPS this matches the PRN.
            int svid = gnssStatus.getSvid(i);

            // Carrier-to-noise density in dB-Hz (equivalent of the old SNR).
            float cn0 = gnssStatus.getCn0DbHz(i);

            // Azimuth of the satellite in degrees.
            float azimuth = gnssStatus.getAzimuthDegrees(i);

            // Elevation of the satellite in degrees.
            float elevation = gnssStatus.getElevationDegrees(i);

            message += ";" + svid
                    + ";" + cn0
                    + ";" + azimuth
                    + ";" + elevation;
        }
        message += ";" + Integer.toString(satCounter);

        logger.log(message);
        logger.log(System.lineSeparator());
    }

    // Legacy logging for API < 24 using the deprecated GpsStatus API
    private Iterable<GpsSatellite> getGpsSatellites(){
        synchronized (this) {
            GpsStatus status = mLocationManager.getGpsStatus(null);
            return  (status == null) ? null : status.getSatellites();
        }
    }

    private void logSatelliteInfo(Iterable<GpsSatellite> gpsSatellites){
        if (gpsSatellites == null) return;

        int satCounter = 0;

        String message = buildHeaderMessage();

        for(GpsSatellite satellite: gpsSatellites){
            satCounter++;

            // PRN (pseudo-random number) for the satellite.
            int prn = satellite.getPrn();

            // Signal to noise ratio for the satellite.
            float snr = satellite.getSnr();

            // Azimuth of the satellite in degrees.
            float azimuth = satellite.getAzimuth();

            // Elevation of the satellite in degrees.
            float elevation = satellite.getElevation();

            message += ";" + prn
                    + ";" + snr
                    + ";" + azimuth
                    + ";" + elevation;
        }
        message += ";" + Integer.toString(satCounter);

        logger.log(message);
        logger.log(System.lineSeparator());
    }

    // Callback for GNSS status changes (replaces the deprecated GpsStatus.Listener, API 24+)
    private class GnssStatusCallback extends GnssStatus.Callback {

        @Override
        public void onStarted() {
            Log.i(TAG, "::onStarted GNSS started");
        }

        @Override
        public void onStopped() {
            Log.i(TAG, "::onStopped GNSS stopped");
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            logGnssStatus(mLastGnssStatus);
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            mLastGnssStatus = status;
            logGnssStatus(status);
        }
    }

    // Legacy listener for GPS status changes (API < 24 only)
    private class GpsStatusListener implements GpsStatus.Listener {

        @Override
        public void onGpsStatusChanged(int event) {
            if (event != GpsStatus.GPS_EVENT_STOPPED) {
                logSatelliteInfo(getGpsSatellites());
            }
        }
    }

    @Override
    public void start() {
        Log.i(TAG, "start:: Starting listener for sensor: " + getSensorName());
        logger.start();
        if (mGnssStatusCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mLocationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(mContext), mGnssStatusCallback);
            } else {
                mLocationManager.registerGnssStatusCallback(mGnssStatusCallback);
            }
        } else if (mGpsStatusListener != null) {
            mLocationManager.addGpsStatusListener(mGpsStatusListener);
        }
    }

    @Override
    public void stop() {
        Log.i(TAG,"stop:: Stopping listener for sensor " + getSensorName());
        logger.stop();
        if (mLocationManager != null) {
            if (mGnssStatusCallback != null) {
                mLocationManager.unregisterGnssStatusCallback(mGnssStatusCallback);
            }
            if (mGpsStatusListener != null) {
                mLocationManager.removeGpsStatusListener(mGpsStatusListener);
            }
        }
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
