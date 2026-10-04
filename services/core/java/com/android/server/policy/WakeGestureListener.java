/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.policy;

import android.os.Handler;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

import java.io.PrintWriter;

/**
 * Watches for wake gesture sensor events then invokes the listener.
 */
public abstract class WakeGestureListener {
    private static final String TAG = "WakeGestureListener";

    private final SensorManager mSensorManager;
    private final Handler mHandler;

    private final Object mLock = new Object();

    private boolean mTriggerRequested;
    private Sensor mSensor;

    public WakeGestureListener(Context context, Handler handler) {
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mHandler = handler;

        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_WAKE_GESTURE);
        if (mSensor == null) {
            // Some sensor HALs expose pickup motion as a wake-up tilt detector.
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_TILT_DETECTOR, true);
        }
    }

    public abstract void onWakeUp();

    public boolean isSupported() {
        synchronized (mLock) {
            return mSensor != null;
        }
    }

    public void requestWakeUpTrigger() {
        synchronized (mLock) {
            if (mSensor != null && !mTriggerRequested) {
                if (mSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                    mTriggerRequested = mSensorManager.requestTriggerSensor(mListener, mSensor);
                } else {
                    mTriggerRequested = mSensorManager.registerListener(mTiltListener, mSensor,
                            SensorManager.SENSOR_DELAY_NORMAL, mHandler);
                }
            }
        }
    }

    public void cancelWakeUpTrigger() {
        synchronized (mLock) {
            if (mSensor != null && mTriggerRequested) {
                mTriggerRequested = false;
                if (mSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                    mSensorManager.cancelTriggerSensor(mListener, mSensor);
                } else {
                    mSensorManager.unregisterListener(mTiltListener, mSensor);
                }
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + TAG);
            prefix += "  ";
            pw.println(prefix + "mTriggerRequested=" + mTriggerRequested);
            pw.println(prefix + "mSensor=" + mSensor);
        }
    }

    private final TriggerEventListener mListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            synchronized (mLock) {
                mTriggerRequested = false;
                mHandler.post(mWakeUpRunnable);
            }
        }
    };

    private final SensorEventListener mTiltListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (mLock) {
                if (!mTriggerRequested) {
                    return;
                }
                // Treat tilt as one-shot, matching the native wake gesture path. Do not
                // depend on the payload: vendor pickup sensors can use a different value.
                mTriggerRequested = false;
                mSensorManager.unregisterListener(this, mSensor);
                mHandler.post(mWakeUpRunnable);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final Runnable mWakeUpRunnable = new Runnable() {
        @Override
        public void run() {
            onWakeUp();
        }
    };
}
