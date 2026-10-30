/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.pm;

import android.content.Context;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;

import java.util.Set;

/**
 * Tracks which packages are denied visibility of other user apps. Profiles follow their
 * parent's list because Settings only manages the parent user.
 */
final class HideAppsController {
    // Replaced whole, so AppsFilter snapshots can read it without locking.
    private static volatile SparseArray<Set<String>> sRestricted = new SparseArray<>();

    private HideAppsController() {}

    static void start(Context context) {
        final Handler handler = BackgroundThread.getHandler();
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.HIDE_APPS_PACKAGES), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        reload(context);
                    }
                }, UserHandle.USER_ALL);
        handler.post(() -> reload(context));
    }

    private static void reload(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final SparseArray<Set<String>> restricted = new SparseArray<>();
        for (UserInfo user : userManager.getUsers()) {
            final UserInfo parent = userManager.getProfileParent(user.id);
            final String raw = Settings.Secure.getStringForUser(context.getContentResolver(),
                    Settings.Secure.HIDE_APPS_PACKAGES, parent != null ? parent.id : user.id);
            if (!TextUtils.isEmpty(raw)) {
                restricted.put(user.id, new ArraySet<>(raw.split(";")));
            }
        }
        sRestricted = restricted;
    }

    static boolean shouldHide(int callingUid, Object callingSetting,
            PackageStateInternal target) {
        final Set<String> packages = sRestricted.get(UserHandle.getUserId(callingUid));
        if (packages == null || target.isSystem() || isSharedLibrary(target)) {
            return false;
        }
        if (callingSetting instanceof PackageStateInternal) {
            return packages.contains(((PackageStateInternal) callingSetting).getPackageName());
        }
        if (callingSetting instanceof SharedUserApi) {
            for (PackageStateInternal state : ((SharedUserApi) callingSetting).getPackageStates()) {
                if (packages.contains(state.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    // Apps link against these at runtime, e.g. the Trichrome library behind WebView.
    private static boolean isSharedLibrary(PackageStateInternal target) {
        final AndroidPackage pkg = target.getPkg();
        return pkg != null && (pkg.getStaticSharedLibraryName() != null
                || pkg.getSdkLibraryName() != null);
    }
}
