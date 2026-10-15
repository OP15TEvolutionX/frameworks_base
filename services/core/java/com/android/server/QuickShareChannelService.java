/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

/** Device opt-in adaptation of the original, unmodified Mosey AIDL v2 service. */
public final class QuickShareChannelService extends SystemService {
    private static final String TAG = "QuickShareChannel";
    private static final String DESCRIPTOR = "com.google.android.moseyservice.IMoseyService";
    private static final String SERVICE = DESCRIPTOR + "/default";
    private static final String PACKAGE = "com.google.android.mosey";
    private static final int VERSION_TRANSACTION = 0x00ffffff;
    private final ChannelBinder mProxy = new ChannelBinder();
    private volatile IBinder mBackend;
    private Handler mHandler;
    private long mPolicyChecked = -2000;
    private int mEligibleUid = -1;

    public QuickShareChannelService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        // A registration notification also delivers an already registered service.
        // This handles native service restarts without polling or restarting Google apps.
        try {
            ServiceManager.registerForNotifications(SERVICE, new IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder service) {
                    if (service != mProxy) mHandler.post(() -> attachBackend(service));
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Cannot observe native service; leaving it unchanged", e);
        }
    }

    private void attachBackend(IBinder backend) {
        if (backend == null || backend == mProxy || backend == mBackend) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!DESCRIPTOR.equals(backend.getInterfaceDescriptor())) return;
            data.writeInterfaceToken(DESCRIPTOR);
            if (!backend.transact(VERSION_TRANSACTION, data, reply, 0)) return;
            reply.readException();
            if (reply.readInt() != 2) {
                Slog.w(TAG, "Untested native API; leaving service unchanged");
                return;
            }
            mBackend = backend;
            ServiceManager.addService(SERVICE, mProxy, false,
                    android.os.IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
            Slog.i(TAG, "Attached to original Mosey AIDL v2 service");
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot attach to native service", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private synchronized boolean canUse149(int uid) {
        long now = SystemClock.elapsedRealtime();
        if (now - mPolicyChecked < 2000) return uid == mEligibleUid;
        mEligibleUid = -1;
        long identity = Binder.clearCallingIdentity();
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(PACKAGE, 0);
            WifiManager wifi = getContext().getSystemService(WifiManager.class);
            long version = info.getLongVersionCode();
            if ((version == 27391 || version == 60715) && info.applicationInfo != null
                    && wifi != null && "RU".equals(wifi.getCountryCode())) {
                for (WifiAvailableChannel channel : wifi.getAllowedChannels(
                        WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS,
                        WifiAvailableChannel.OP_MODE_WIFI_AWARE)) {
                    if (channel.getFrequencyMhz() == 5745) {
                        mEligibleUid = info.applicationInfo.uid;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Channel policy unavailable; leaving request unchanged");
        } finally {
            Binder.restoreCallingIdentity(identity);
            mPolicyChecked = now;
        }
        return uid == mEligibleUid;
    }

    // Stable AIDL StartArgs: presence, parcelable size, int[] channels. Never
    // deserialize/rebuild the remaining payload, which includes Binder callbacks.
    static int channelOffset(Parcel parcel) {
        int saved = parcel.dataPosition();
        try {
            parcel.setDataPosition(0);
            parcel.enforceInterface(DESCRIPTOR);
            if (parcel.readInt() != 1) return -1;
            int start = parcel.dataPosition();
            int size = parcel.readInt();
            if (size < 16 || size > parcel.dataSize() - start) return -1;
            if (parcel.readInt() != 1) return -1;
            int offset = parcel.dataPosition();
            if (offset + 4 > start + size || parcel.readInt() != 44) return -1;
            return offset;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            parcel.setDataPosition(saved);
        }
    }

    private final class ChannelBinder extends Binder {
        ChannelBinder() {
            attachInterface(null, DESCRIPTOR);
            markVintfStability();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            IBinder backend = mBackend;
            if (backend == null) throw new RemoteException("Native Mosey service unavailable");
            Parcel copy = Parcel.obtain();
            try {
                copy.appendFrom(data, 0, data.dataSize());
                copy.setDataPosition(0);
                int offset = code == 1 ? channelOffset(copy) : -1;
                if (offset >= 0 && canUse149(Binder.getCallingUid())) {
                    copy.setDataPosition(offset);
                    copy.writeInt(149);
                    Slog.i(TAG, "START channel=44->149; original country preserved");
                }
                copy.setDataPosition(0);
                return backend.transact(code, copy, reply, flags);
            } finally {
                copy.recycle();
            }
        }
    }
}
