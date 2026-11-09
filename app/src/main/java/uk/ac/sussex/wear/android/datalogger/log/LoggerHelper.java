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

package uk.ac.sussex.wear.android.datalogger.log;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by fjordonez on 13/10/16.
 */

// class for storing logs in log files, creating directories

public final class LoggerHelper {

    private static final String TAG = LoggerHelper.class.getSimpleName();

    public static File defineLogFilename(Context context, String path, String baseFilename, String extension, boolean toAppend, long nanosOffset){

        // Get the absolute path to the directory on the primary shared/external storage device
        // where the application can place persistent files.
        File absoluteDir = new File(context.getExternalFilesDir(null), path);
        if (!absoluteDir.exists()){
            absoluteDir.mkdirs();
        }

        String nanoTimeString = Long.toString(SystemClock.elapsedRealtimeNanos() + nanosOffset);
        File[] files = absoluteDir.listFiles();
        if (files.length == 0) { // In case there are no files in the dir
            // The first logging file is indexed as 0
            baseFilename = baseFilename + "__" + nanoTimeString + "__0" + "." + extension;
        } else {
            int lastIndex = -1;
            for (File file : files){
                if (file.isFile()){
                    String[] items = file.getName().split("\\.")[0].split("__");
                    // There must be two "__" tokens in the file name: sessionName__nanoTime__index
                    // items[0]=sessionName, items[1]=nanoTime. items[2]=index
                    int index = Integer.parseInt(items[2]);
                    // The index of the last file is obtained
                    if ((index > lastIndex) && (items[0].equals(baseFilename))){
                        lastIndex = index;
                        if (toAppend){
                            nanoTimeString = items[1];
                        }
                    }
                }
            }
            if (toAppend){
                baseFilename = baseFilename + "__" + nanoTimeString + "__" + lastIndex + "." + extension;
            } else {
                baseFilename = baseFilename + "__" + nanoTimeString + "__" + (lastIndex+1) + "." + extension;
            }
        }

        return new File(absoluteDir.getAbsolutePath() + File.separator + baseFilename);
    }

    /**
     * Copies a finished log file into the shared Downloads folder (Downloads/DataLogger) via
     * MediaStore, so the user can see and share the files with a regular file manager.
     * On Android 10+ (API 29+) apps can create their own files in Downloads without any
     * storage permission. On older versions this is a no-op (files stay in the app-specific dir).
     */
    public static void copyToDownloads(Context context, File source) {
        if (source == null || !source.exists()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DataLogger");
            values.put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(source.getName()));
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.w(TAG, "copyToDownloads: could not create Downloads entry for " + source.getName());
                return;
            }
            try (OutputStream out = resolver.openOutputStream(uri);
                 InputStream in = new FileInputStream(source)) {
                if (out != null && in != null) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            Log.i(TAG, "copyToDownloads: copied " + source.getName() + " to Downloads/DataLogger");
        } catch (Exception e) {
            Log.w(TAG, "copyToDownloads: failed for " + source.getName() + ": " + e.getMessage());
        }
    }

    private static String mimeTypeFor(String filename) {
        if (filename.endsWith(".txt")) return "text/plain";
        if (filename.endsWith(".3gp")) return "audio/3gpp";
        return "application/octet-stream";
    }

}
