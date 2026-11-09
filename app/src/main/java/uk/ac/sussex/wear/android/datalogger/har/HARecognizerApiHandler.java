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

package uk.ac.sussex.wear.android.datalogger.har;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.DetectedActivity;

import java.io.File;
import java.util.ArrayList;

import uk.ac.sussex.wear.android.datalogger.Constants;
import uk.ac.sussex.wear.android.datalogger.log.HARecognizerApiLogger;

// class for using google activity recognition (replaces the deprecated GoogleApiClient API
// with the ActivityRecognitionClient)

public class HARecognizerApiHandler {

    private static final String TAG = HARecognizerApiHandler.class.getSimpleName();

    /**
     * A receiver for DetectedActivity objects broadcast by the
     * {@code HARecognizerApiIntentService}.
     */
    private HARecognizerBroadcastReceiver mBroadcastReceiver;

    /**
     * Client for the ActivityRecognition API (replaces the deprecated GoogleApiClient).
     */
    private ActivityRecognitionClient mActivityRecognitionClient;

    private HARecognizerApiLogger mHARecognizerApiLogger;

    private Context mContext;

    /**
     * The desired time between activity detections. Larger values result in fewer activity
     * detections while improving battery life. A value of 0 results in activity detections at the
     * fastest possible rate. Getting frequent updates negatively impact battery life.
     */
    private int mdetectionIntervalHAR;

    public HARecognizerApiHandler(Context context, String sessionName, int detectionIntervalHAR, long nanosOffset, int logFileMaxSize){

        mContext = context;

        mdetectionIntervalHAR = detectionIntervalHAR;

        // Get the ActivityRecognitionClient (replaces the deprecated GoogleApiClient)
        mActivityRecognitionClient = ActivityRecognition.getClient(mContext);

        String path = sessionName + File.separator + Constants.SENSOR_NAME_API_HAR + "_" + sessionName;

        mHARecognizerApiLogger = new HARecognizerApiLogger(mContext,
                path,
                sessionName,
                nanosOffset,
                logFileMaxSize);

        // Get a receiver for broadcasts from HARecognizerApiIntentService.
        mBroadcastReceiver = new HARecognizerBroadcastReceiver();
    }

    public void start(){

        mHARecognizerApiLogger.start();

        requestActivityUpdates();

        // Register the broadcast receiver that informs this activity of the DetectedActivity
        // object broadcast sent by the intent service.
        LocalBroadcastManager.getInstance(mContext).registerReceiver(mBroadcastReceiver,
                new IntentFilter(Constants.BROADCAST_ACTION));
    }

    public void stop(){

        //Removes activity recognition updates
        removeActivityUpdates();

        // Unregister the broadcast receiver that was registered
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mBroadcastReceiver);

        mHARecognizerApiLogger.stop();
    }

    public void haltAndRestartLogging() {
        mHARecognizerApiLogger.stop();
        mHARecognizerApiLogger.resetByteCounter();
        mHARecognizerApiLogger.start();
    }

    /**
     * Gets a PendingIntent to be sent for each activity detection.
     */
    private PendingIntent getActivityDetectionPendingIntent() {
        Intent intent = new Intent(mContext, HARecognizerApiIntentService.class);

        // We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
        // requestActivityUpdates() and removeActivityUpdates(). FLAG_IMMUTABLE is required on
        // API 31+.
        return PendingIntent.getService(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Registers for activity recognition updates using the ActivityRecognitionClient.
     */
    private void requestActivityUpdates() {
        mActivityRecognitionClient.requestActivityUpdates(
                mdetectionIntervalHAR,
                getActivityDetectionPendingIntent()
        ).addOnFailureListener(e ->
                Log.e(TAG, "Error adding activity detection: " + e.getMessage()));
    }

    /**
     * Removes activity recognition updates using the ActivityRecognitionClient.
     */
    private void removeActivityUpdates() {
        // Remove all activity updates for the PendingIntent that was used to request activity
        // updates.
        mActivityRecognitionClient.removeActivityUpdates(
                getActivityDetectionPendingIntent()
        ).addOnFailureListener(e ->
                Log.e(TAG, "Error removing activity detection: " + e.getMessage()));
    }

    public void updateNanosOffset(long nanosOffset) {
        mHARecognizerApiLogger.updateNanosOffset(nanosOffset);
    }

    /**
     * Receiver for intents sent by HARecognizerApiIntentService via a sendBroadcast().
     * Receives a list of one or more DetectedActivity objects associated with the current state of
     * the device.
     */
    public class HARecognizerBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            ArrayList<DetectedActivity> detectedActivities;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                detectedActivities = intent.getParcelableArrayListExtra(
                        Constants.DETECTED_ACTIVITIES_INTENT_KEY, DetectedActivity.class);
            } else {
                detectedActivities = intent.getParcelableArrayListExtra(
                        Constants.DETECTED_ACTIVITIES_INTENT_KEY);
            }

            mHARecognizerApiLogger.logDetectedActivities(detectedActivities);
        }
    }

}
