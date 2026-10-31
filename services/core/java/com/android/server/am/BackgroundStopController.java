/* Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.am;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.UidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.util.List;

/** Force stops Component Manager's background-denied packages after the screen locks. */
final class BackgroundStopController {
    private static final String TAG = "BackgroundStopController";
    private static final int DEFAULT_DELAY_SEC = 60;
    private static final long WAKE_LOCK_MS = 60_000;

    private final Context mContext;
    private final ActivityManagerService mService;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final ArraySet<String> mTargets = new ArraySet<>();
    private final AlarmManager.OnAlarmListener mAlarm = this::stopPackages;
    private final UidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidGone(int uid, boolean disabled) {
            mHandler.post(() -> handleUidGone(uid));
        }
    };

    private AlarmManager mAlarmManager;
    private PowerManager.WakeLock mWakeLock;
    private boolean mLocked;
    private boolean mExpired;
    private boolean mObserving;

    BackgroundStopController(Context context, ActivityManagerService service) {
        mContext = context;
        mService = service;
    }

    void start() {
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mWakeLock = mContext.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.COMPONENT_MANAGER_STOP_PACKAGES),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        loadTargets();
                    }
                });
        loadTargets();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    onLocked();
                } else {
                    onUnlocked();
                }
            }
        }, filter, null, mHandler);
    }

    private void loadTargets() {
        mTargets.clear();
        String raw = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.COMPONENT_MANAGER_STOP_PACKAGES);
        if (!TextUtils.isEmpty(raw)) {
            for (String entry : raw.split(";")) {
                if (entry.indexOf(':') > 0) {
                    mTargets.add(entry);
                }
            }
        }
    }

    private void onLocked() {
        if (mLocked || mTargets.isEmpty()) {
            return;
        }
        mLocked = true;
        mExpired = false;
        mService.registerUidObserver(mUidObserver, ActivityManager.UID_OBSERVER_GONE,
                ActivityManager.PROCESS_STATE_UNKNOWN, "android");
        mObserving = true;
        int delaySec = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.COMPONENT_MANAGER_STOP_DELAY_SEC, DEFAULT_DELAY_SEC);
        schedule(Math.max(delaySec, 0) * 1000L);
    }

    private void onUnlocked() {
        mLocked = false;
        mExpired = false;
        mAlarmManager.cancel(mAlarm);
        if (mObserving) {
            mService.unregisterUidObserver(mUidObserver);
            mObserving = false;
        }
    }

    private void schedule(long delayMs) {
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, TAG, mHandler::post, null, mAlarm);
    }

    private void stopPackages() {
        if (!mLocked) {
            return;
        }
        mWakeLock.acquire(WAKE_LOCK_MS);
        try {
            mExpired = true;
            List<ActivityManager.RunningAppProcessInfo> processes =
                    mService.getRunningAppProcesses();
            if (processes != null) {
                ArraySet<String> protectedPackages = protectedPackages();
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pkgList == null) {
                        continue;
                    }
                    int userId = UserHandle.getUserId(process.uid);
                    for (String pkg : process.pkgList) {
                        if (!mTargets.contains(userId + ":" + pkg)
                                || protectedPackages.contains(pkg)) {
                            continue;
                        }
                        forceStop(pkg, userId);
                    }
                }
            }
        } finally {
            mWakeLock.release();
        }
    }

    private void handleUidGone(int uid) {
        if (!mExpired) {
            return;
        }
        int userId = UserHandle.getUserId(uid);
        PackageManager pm = mContext.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) {
            return;
        }
        for (String pkg : packages) {
            if (mTargets.contains(userId + ":" + pkg) && !isStopped(pm, pkg, userId)) {
                forceStop(pkg, userId);
            }
        }
    }

    private static boolean isStopped(PackageManager pm, String pkg, int userId) {
        try {
            return (pm.getApplicationInfoAsUser(pkg, 0, userId).flags
                    & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void forceStop(String pkg, int userId) {
        try {
            mService.forceStopPackage(pkg, userId);
            Slog.i(TAG, "Stopped " + pkg + " for user " + userId);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to stop " + pkg, e);
        }
    }

    private ArraySet<String> protectedPackages() {
        ArraySet<String> packages = new ArraySet<>();
        packages.add("android");
        packages.add("com.android.systemui");
        ResolveInfo home = mContext.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) {
            packages.add(home.activityInfo.packageName);
        }
        return packages;
    }
}
