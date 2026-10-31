/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.pm;

import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
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

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks which packages are denied visibility of other apps. Profiles follow their parent's
 * lists because Settings only manages the parent user.
 */
public final class HideAppsController {
    private static final class UserState {
        final Set<String> mPackages;
        final Set<String> mStrictPackages;
        final Set<String> mRoleHolders;
        final Set<String> mPlayPackages;

        UserState(Set<String> packages, Set<String> strictPackages, Set<String> roleHolders,
                Set<String> playPackages) {
            mPackages = packages;
            mStrictPackages = strictPackages;
            mRoleHolders = roleHolders;
            mPlayPackages = playPackages;
        }
    }

    private static final String PLAY_STORE = "com.android.vending";
    // Changes on every boot, so the overlay picks can't be matched across reboots.
    private static final long OVERLAY_SALT = new SecureRandom().nextLong();

    // Apps break without these even when they never name them directly.
    private static final Set<String> PROTECTED_PACKAGES = Set.of(
            "android",
            "android.media",
            "com.android.credentialmanager",
            "com.android.documentsui",
            "com.android.externalstorage",
            "com.android.intentresolver",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.providers.blockednumber",
            "com.android.providers.calendar",
            "com.android.providers.contacts",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.providers.userdictionary",
            "com.android.vending",
            "com.android.webview",
            "com.google.android.documentsui",
            "com.google.android.ext.services",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.providers.media.module",
            "com.google.android.webview");

    private static final String[] PROTECTED_ROLES = {
            RoleManager.ROLE_ASSISTANT,
            RoleManager.ROLE_BROWSER,
            RoleManager.ROLE_DIALER,
            RoleManager.ROLE_HOME,
            RoleManager.ROLE_SMS,
    };

    // Replaced whole, so AppsFilter snapshots can read it without locking.
    private static volatile SparseArray<UserState> sStates = new SparseArray<>();
    private static boolean sRolesObserved;

    private HideAppsController() {}

    static void start(Context context) {
        final Handler handler = BackgroundThread.getHandler();
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reload(context);
            }
        };
        for (String key : new String[] {Settings.Secure.HIDE_APPS_PACKAGES,
                Settings.Secure.HIDE_APPS_STRICT_PACKAGES,
                Settings.Secure.HIDE_APPS_PLAY_PACKAGES,
                Settings.Secure.DEFAULT_INPUT_METHOD}) {
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        // Role holders are only reliable once the user is unlocked.
        context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!sRolesObserved) {
                    sRolesObserved = true;
                    context.getSystemService(RoleManager.class)
                            .addOnRoleHoldersChangedListenerAsUser(handler::post,
                                    (role, user) -> reload(context), UserHandle.ALL);
                }
                reload(context);
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, handler);
        handler.post(() -> reload(context));
    }

    private static void reload(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final SparseArray<UserState> states = new SparseArray<>();
        for (UserInfo user : userManager.getUsers()) {
            final UserInfo parent = userManager.getProfileParent(user.id);
            final int ownerId = parent != null ? parent.id : user.id;
            final Set<String> packages = readPackages(context,
                    Settings.Secure.HIDE_APPS_PACKAGES, ownerId);
            final Set<String> strictPackages = readPackages(context,
                    Settings.Secure.HIDE_APPS_STRICT_PACKAGES, ownerId);
            final Set<String> playPackages = readPackages(context,
                    Settings.Secure.HIDE_APPS_PLAY_PACKAGES, ownerId);
            if (packages.isEmpty() && strictPackages.isEmpty() && playPackages.isEmpty()) {
                continue;
            }
            final Set<String> roleHolders = new ArraySet<>();
            final UserHandle owner = UserHandle.of(ownerId);
            for (String role : PROTECTED_ROLES) {
                roleHolders.addAll(roleManager.getRoleHoldersAsUser(role, owner));
            }
            final ComponentName ime = ComponentName.unflattenFromString(String.valueOf(
                    Settings.Secure.getStringForUser(context.getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD, ownerId)));
            if (ime != null) {
                roleHolders.add(ime.getPackageName());
            }
            states.put(user.id, new UserState(packages, strictPackages, roleHolders,
                    playPackages));
        }
        sStates = states;
    }

    private static Set<String> readPackages(Context context, String key, int userId) {
        final String raw = Settings.Secure.getStringForUser(context.getContentResolver(), key,
                userId);
        return TextUtils.isEmpty(raw) ? Set.of() : new ArraySet<>(raw.split(";"));
    }

    static boolean shouldHide(int callingUid, Object callingSetting,
            PackageStateInternal target) {
        final UserState state = sStates.get(UserHandle.getUserId(callingUid));
        if (state == null || isSharedLibrary(target)
                || PROTECTED_PACKAGES.contains(target.getPackageName())) {
            return false;
        }
        if (state.mPlayPackages.contains(target.getPackageName())
                && matches(callingSetting, PLAY_STORE::equals)) {
            return true;
        }
        final boolean strict = matches(callingSetting, state.mStrictPackages::contains);
        if (target.isSystem()) {
            if (strict && isStockOverlay(target)) {
                return !showsOverlay(callingSetting, target.getPackageName());
            }
            return strict && !state.mRoleHolders.contains(target.getPackageName());
        }
        return strict || matches(callingSetting, state.mPackages::contains);
    }

    /**
     * Whether {@code target} is hidden from a restricted caller. Unlike a plain visibility check
     * this leaves unrestricted callers alone, so call sites keep stock behavior for them.
     */
    public static boolean isHiddenFrom(PackageManagerInternal pm, int callingUid, String target,
            int userId) {
        final UserState state = sStates.get(UserHandle.getUserId(callingUid));
        if (state == null) {
            return false;
        }
        final AndroidPackage caller = pm.getPackage(callingUid);
        return caller != null && (state.mPackages.contains(caller.getPackageName())
                || state.mStrictPackages.contains(caller.getPackageName())
                || (PLAY_STORE.equals(caller.getPackageName()) && !state.mPlayPackages.isEmpty()))
                && pm.filterAppAccess(target, callingUid, userId);
    }

    private static boolean matches(Object callingSetting, Predicate<String> packages) {
        if (callingSetting instanceof PackageStateInternal) {
            return packages.test(((PackageStateInternal) callingSetting).getPackageName());
        }
        if (callingSetting instanceof SharedUserApi) {
            for (PackageStateInternal state : ((SharedUserApi) callingSetting).getPackageStates()) {
                if (packages.test(state.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    // Overlays that ship on many devices; each restricted app sees its own share of them, so
    // the visible app count differs between apps and between boots.
    private static boolean isStockOverlay(PackageStateInternal target) {
        final AndroidPackage pkg = target.getPkg();
        if (pkg == null || !pkg.isResourceOverlay()) {
            return false;
        }
        final String name = target.getPackageName();
        // Theme overlays are this ROM's own and would make up most of the pool.
        return (name.startsWith("com.android.") && !name.startsWith("com.android.theme."))
                || name.startsWith("com.google.") || name.startsWith("android.overlay.");
    }

    private static boolean showsOverlay(Object callingSetting, String overlay) {
        final String caller;
        if (callingSetting instanceof PackageStateInternal) {
            caller = ((PackageStateInternal) callingSetting).getPackageName();
        } else if (callingSetting instanceof SharedUserApi) {
            caller = ((SharedUserApi) callingSetting).getName();
        } else {
            return false;
        }
        // The caller's own share, then a stable pick of overlays within it.
        final int share = Math.floorMod(Objects.hash(OVERLAY_SALT, caller), 101);
        return Math.floorMod(Objects.hash(OVERLAY_SALT, caller, overlay), 100) < share;
    }

    // Apps link against these at runtime, e.g. the Trichrome library behind WebView.
    private static boolean isSharedLibrary(PackageStateInternal target) {
        final AndroidPackage pkg = target.getPkg();
        return pkg != null && (pkg.getStaticSharedLibraryName() != null
                || pkg.getSdkLibraryName() != null);
    }
}
