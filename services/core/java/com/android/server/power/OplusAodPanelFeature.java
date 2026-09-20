/*
 * Copyright (C) 2026 The PixelOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.power;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import java.util.NoSuchElementException;

/**
 * Framework client for the OPlus panel AOD backlight mode.
 *
 * <p>ColorOS sends feature 7 through IDisplayPanelFeature: mode 0 is the
 * bright AOD level and mode 1 is the dark AOD level. This raw Binder client
 * keeps the vendor AIDL Java backend out of the generic system_server build.</p>
 */
final class OplusAodPanelFeature {
    private static final String TAG = "OplusAodPanelFeature";
    private static final String SERVICE_NAME =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature/default";
    private static final String DESCRIPTOR =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature";
    private static final int FEATURE_AOD_BRIGHTNESS = 7;
    private static final int TRANSACTION_SET_DISPLAY_PANEL_FEATURE_VALUE =
            IBinder.FIRST_CALL_TRANSACTION + 1;

    private static IBinder sBinder;
    private static int sLastMode = -1;

    private OplusAodPanelFeature() {}

    static synchronized void setAodMode(int mode) {
        if ((mode != 0 && mode != 1) || mode == sLastMode) {
            return;
        }

        Parcel data = null;
        Parcel reply = null;
        try {
            if (sBinder == null) {
                sBinder = ServiceManager.getService(SERVICE_NAME);
            }
            if (sBinder == null) {
                Slog.w(TAG, "AOD panel feature service is unavailable");
                return;
            }

            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(FEATURE_AOD_BRIGHTNESS);
            data.writeIntArray(new int[] {mode});
            if (!sBinder.transact(TRANSACTION_SET_DISPLAY_PANEL_FEATURE_VALUE,
                    data, reply, 0)) {
                Slog.w(TAG, "Panel feature transaction was rejected");
                sBinder = null;
                return;
            }
            reply.readException();
            final int result = reply.readInt();
            sLastMode = mode;
            Slog.d(TAG, "set AOD panel mode=" + mode + " result=" + result);
        } catch (RemoteException | SecurityException | NoSuchElementException e) {
            Slog.w(TAG, "Unable to set AOD panel mode=" + mode, e);
            sBinder = null;
        } finally {
            if (reply != null) {
                reply.recycle();
            }
            if (data != null) {
                data.recycle();
            }
        }
    }

    /** Sends the dark-mode command for a new Doze cycle even if the cached mode matches. */
    static synchronized void prepareDarkAodMode() {
        sLastMode = -1;
        setAodMode(1);
    }

    /** Re-sends the mode selected before the panel enters its low-power state. */
    static synchronized void reapplyAodMode() {
        final int mode = sLastMode == 0 || sLastMode == 1 ? sLastMode : 1;
        sLastMode = -1;
        setAodMode(mode);
    }

    static synchronized void resetCache() {
        sLastMode = -1;
    }
}
