/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.pm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.evolution.FingerprintDefender;
import com.android.server.pm.pkg.PackageStateInternal;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gives apps protected by Fingerprint Defender install times derived from their own seed, so
 * the flash date and the set of install times no longer match across apps.
 */
public final class FingerprintDefenderController {
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static final class UserState {
        final String mPackages;
        final String mSalt;

        UserState(String packages, String salt) {
            mPackages = packages;
            mSalt = salt;
        }
    }

    // Replaced whole, so package queries can read it without locking.
    private static volatile SparseArray<UserState> sStates = new SparseArray<>();

    private FingerprintDefenderController() {}

    static void start(Context context) {
        final Handler handler = BackgroundThread.getHandler();
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reload(context);
            }
        };
        for (String key : new String[] {FingerprintDefender.KEY_PACKAGES,
                FingerprintDefender.KEY_SALT}) {
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        handler.post(() -> reload(context));
    }

    private static void reload(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final SparseArray<UserState> states = new SparseArray<>();
        for (UserInfo user : userManager.getUsers()) {
            final UserInfo parent = userManager.getProfileParent(user.id);
            final int ownerId = parent != null ? parent.id : user.id;
            final String packages = Settings.Secure.getStringForUser(
                    context.getContentResolver(), FingerprintDefender.KEY_PACKAGES, ownerId);
            if (TextUtils.isEmpty(packages)) {
                continue;
            }
            String salt = Settings.Secure.getStringForUser(context.getContentResolver(),
                    FingerprintDefender.KEY_SALT, ownerId);
            if (TextUtils.isEmpty(salt)) {
                final byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                salt = HexFormat.of().formatHex(bytes);
                Settings.Secure.putStringForUser(context.getContentResolver(),
                        FingerprintDefender.KEY_SALT, salt, ownerId);
            }
            states.put(user.id, new UserState(packages, salt));
        }
        sStates = states;
    }

    /** The protected caller's package, or null when the caller sees real data. */
    private static String protectedCaller(Computer computer, int callingUid, UserState state) {
        if (state == null || UserHandle.getAppId(callingUid) < Process.FIRST_APPLICATION_UID) {
            return null;
        }
        final String[] packages = computer.getPackagesForUid(callingUid);
        if (packages == null) {
            return null;
        }
        for (String name : packages) {
            if (FingerprintDefender.getEpoch(state.mPackages, name) >= 0) {
                return name;
            }
        }
        return null;
    }

    /** Replaces install times of other packages with ones derived from the caller's seed. */
    static void maskInstallTimes(Computer computer, PackageInfo info, PackageStateInternal ps,
            int callingUid, int userId) {
        final int callerUser = UserHandle.getUserId(callingUid);
        final UserState state = sStates.get(callerUser);
        final String caller = protectedCaller(computer, callingUid, state);
        if (caller == null || caller.equals(info.packageName)) {
            return;
        }
        final int epoch = FingerprintDefender.getEpoch(state.mPackages, caller);
        final long now = System.currentTimeMillis();
        // The apparent first boot: 2 weeks to a year before the real one, at a random time.
        final PackageStateInternal platform = computer.getPackageStateInternal("android");
        // Not before the build: a first boot with an unset clock is stamped near 1970.
        final long realBoot = Math.max(Build.TIME, platform != null
                ? platform.getUserStateOrDefault(userId).getFirstInstallTimeMillis() : now);
        final long bootSeed = FingerprintDefender.seed(state.mSalt, callerUser, caller, epoch,
                "boot");
        final long boot = Math.min(realBoot, now) - (14 + bootSeed % 352) * DAY
                - (bootSeed >>> 9) % DAY;
        final long target = FingerprintDefender.seed(state.mSalt, callerUser, caller, epoch,
                info.packageName);
        final long span = Math.max(DAY, now - boot);
        final boolean updated = info.lastUpdateTime > info.firstInstallTime;
        if (ps.isSystem()) {
            // System apps came with the build, so they share the first boot time.
            info.firstInstallTime = boot;
            info.lastUpdateTime = updated ? boot + target % span : boot;
        } else {
            info.firstInstallTime = boot + target % span;
            final long rest = Math.max(DAY, now - info.firstInstallTime);
            info.lastUpdateTime = updated
                    ? info.firstInstallTime + (target >>> 17) % rest : info.firstInstallTime;
        }
    }

    /** Whether a protected app asks for an install source; third-party apps then look from Play. */
    static boolean isMaskedInstallQuery(Computer computer, int callingUid, String packageName) {
        final UserState state = sStates.get(UserHandle.getUserId(callingUid));
        return packageName != null && protectedCaller(computer, callingUid, state) != null;
    }
}
