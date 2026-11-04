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
import com.android.internal.util.evolution.FingerprintDefender;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks which packages are denied visibility of other apps. Profiles follow their parent's
 * lists because Settings only manages the parent user.
 *
 * <p>Modes, from the Settings lists:
 * <ul>
 * <li>hide_apps_packages: the app doesn't see other non-system apps.
 * <li>hide_apps_strict_packages: it also doesn't see system apps, except role holders, the
 *     input method and {@link #PROTECTED_PACKAGES}. It sees a stable random share of stock
 *     overlays, so its app count looks like a normal device's and differs between apps.
 * <li>hide_apps_play_packages: Google Play doesn't see these apps, so it stops updating them.
 * </ul>
 * AppsFilter asks {@link #shouldHide}; other system services ask {@link #isHiddenFrom}.
 */
public final class HideAppsController {
    /** Lists of one user (profiles get their parent's), read from Settings. */
    private static final class UserState {
        final Set<String> mPackages;
        final Set<String> mStrictPackages;
        final Set<String> mRoleHolders;
        final Set<String> mPlayPackages;
        /** Device secret behind the overlay picks; created once per user. */
        final String mSalt;
        /** "pkg:epoch" entries; a new epoch gives the app a new overlay pick. */
        final String mEpochs;

        UserState(Set<String> packages, Set<String> strictPackages, Set<String> roleHolders,
                Set<String> playPackages, String salt, String epochs) {
            mPackages = packages;
            mStrictPackages = strictPackages;
            mRoleHolders = roleHolders;
            mPlayPackages = playPackages;
            mSalt = salt;
            mEpochs = epochs;
        }
    }

    private static final String PLAY_STORE = "com.android.vending";

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
    // Only touched on the background handler.
    private static boolean sRolesObserved;

    private HideAppsController() {}

    /** Called once PackageManager is ready; reloads on every change of the inputs. */
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
                Settings.Secure.HIDE_APPS_EPOCHS,
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

    /** Rebuilds the state of every user that has a list; runs on the background thread. */
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
            // Like Fingerprint Defender: a lasting salt, and a per-app epoch to reset with.
            String salt = Settings.Secure.getStringForUser(context.getContentResolver(),
                    Settings.Secure.HIDE_APPS_SALT, ownerId);
            if (TextUtils.isEmpty(salt)) {
                final byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                salt = HexFormat.of().formatHex(bytes);
                Settings.Secure.putStringForUser(context.getContentResolver(),
                        Settings.Secure.HIDE_APPS_SALT, salt, ownerId);
            }
            final String epochs = Settings.Secure.getStringForUser(context.getContentResolver(),
                    Settings.Secure.HIDE_APPS_EPOCHS, ownerId);
            states.put(user.id, new UserState(packages, strictPackages, roleHolders,
                    playPackages, salt, epochs));
        }
        sStates = states;
    }

    private static Set<String> readPackages(Context context, String key, int userId) {
        final String raw = Settings.Secure.getStringForUser(context.getContentResolver(), key,
                userId);
        return TextUtils.isEmpty(raw) ? Set.of() : new ArraySet<>(raw.split(";"));
    }

    /**
     * Whether AppsFilter should hide {@code target} from the caller, a package or shared user.
     * Protected packages and shared libraries are never hidden, since apps break without them.
     */
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
                return !showsOverlay(state, callingSetting, target.getPackageName());
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
        if (caller == null) {
            return false;
        }
        final String name = caller.getPackageName();
        final boolean restricted = state.mPackages.contains(name)
                || state.mStrictPackages.contains(name)
                || (PLAY_STORE.equals(name) && !state.mPlayPackages.isEmpty());
        return restricted && pm.filterAppAccess(target, callingUid, userId);
    }

    /** Whether the caller, or any package of its shared user, is in {@code packages}. */
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
    // the visible app count differs between apps until the app is reset.
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

    /**
     * Whether the caller sees this stock overlay. The caller gets a share of 0-100% from the
     * salt, its epoch and its name, and each overlay is in or out by the same hash, so the pick
     * stays the same until the app is reset in Settings.
     */
    private static boolean showsOverlay(UserState state, Object callingSetting, String overlay) {
        final String caller;
        if (callingSetting instanceof PackageStateInternal) {
            caller = ((PackageStateInternal) callingSetting).getPackageName();
        } else if (callingSetting instanceof SharedUserApi) {
            caller = ((SharedUserApi) callingSetting).getName();
        } else {
            return false;
        }
        final int epoch = FingerprintDefender.getEpoch(state.mEpochs, caller);
        final int share = Math.floorMod(Objects.hash(state.mSalt, epoch, caller), 101);
        return Math.floorMod(Objects.hash(state.mSalt, epoch, caller, overlay), 100) < share;
    }

    // Apps link against these at runtime, e.g. the Trichrome library behind WebView.
    private static boolean isSharedLibrary(PackageStateInternal target) {
        final AndroidPackage pkg = target.getPkg();
        return pkg != null && (pkg.getStaticSharedLibraryName() != null
                || pkg.getSdkLibraryName() != null);
    }
}
