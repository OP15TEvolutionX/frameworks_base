/*
 * Copyright (C) 2026 The PixelOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.power;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Slog;

/**
 * ColorOS-compatible listener for Qualcomm's AOD light sensor.
 *
 * <p>The stock OPlusPowerManagerHelper reads the first value of the binary
 * qti.sensor.lux_aod event and processes it on its Handler after 500 ms.
 * Values 1 and 0 select the bright and dark panel modes respectively.</p>
 */
final class OplusAodLightSensor implements SensorEventListener {
    private static final String TAG = "OplusAodLightSensor";
    private static final int TYPE_AOD_LIGHT_QCOM = 33171032;
    private static final long PROCESS_DELAY_MILLIS = 500L;

    private final SensorManager mSensorManager;
    private final Handler mHandler;
    private final Sensor mSensor;
    private final Runnable mProcessEvent = this::processPendingEvent;

    private volatile boolean mEnabled;
    private volatile float mPendingValue;

    OplusAodLightSensor(SensorManager sensorManager, Handler handler) {
        mSensorManager = sensorManager;
        mHandler = handler;
        mSensor = sensorManager.getDefaultSensor(TYPE_AOD_LIGHT_QCOM, true);
        mPendingValue = Float.NaN;
        if (mSensor == null) {
            Slog.w(TAG, "qti.sensor.lux_aod is unavailable");
        }
    }

    synchronized void setEnabled(boolean enabled) {
        if (enabled == mEnabled) {
            return;
        }

        if (enabled) {
            if (mSensor == null) {
                return;
            }
            mPendingValue = Float.NaN;
            OplusAodPanelFeature.resetCache();
            mEnabled = mSensorManager.registerListener(this, mSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Slog.d(TAG, "lux_aod listener enabled=" + mEnabled);
        } else {
            mEnabled = false;
            mHandler.removeCallbacks(mProcessEvent);
            mSensorManager.unregisterListener(this);
            mPendingValue = Float.NaN;
            OplusAodPanelFeature.resetCache();
            Slog.d(TAG, "lux_aod listener disabled");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mEnabled || event.values == null || event.values.length == 0) {
            return;
        }

        final float value = event.values[0];
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return;
        }

        mPendingValue = value;
        Slog.d(TAG, "lux_aod event values[0]=" + value
                + " count=" + event.values.length);
        mHandler.removeCallbacks(mProcessEvent);
        mHandler.postDelayed(mProcessEvent, PROCESS_DELAY_MILLIS);
    }

    private void processPendingEvent() {
        if (!mEnabled) {
            return;
        }

        final float value = mPendingValue;
        if (Float.compare(value, 1.0f) == 0) {
            Slog.d(TAG, "lux_aod processed bright state");
            OplusAodPanelFeature.setAodMode(0);
        } else if (Float.compare(value, 0.0f) == 0) {
            Slog.d(TAG, "lux_aod processed dark state");
            OplusAodPanelFeature.setAodMode(1);
        } else {
            Slog.d(TAG, "lux_aod ignored unsupported value=" + value);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
