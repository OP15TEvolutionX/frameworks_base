/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Shared state of Fingerprint Defender. Selected apps see a stock build, coarse hardware numbers
 * and, from system_server, install times derived from a per-app seed, so SDKs in different apps
 * can't link their device profiles.
 *
 * @hide
 */
public final class FingerprintDefender {
    private static final String TAG = "FingerprintDefender";

    /** Semicolon-separated {@code package:epoch} entries; the epoch changes the app's seed. */
    public static final String KEY_PACKAGES = "fingerprint_defender_packages";
    /** Random hex salt; regenerating it changes every app's seed. */
    public static final String KEY_SALT = "fingerprint_defender_salt";

    private static final long GB = 1L << 30;
    private static final long MAX_FREE_OFFSET_GB = 8;
    private static final int MIN_BATTERY_LEVEL = 20;

    // Release date and builder of the public build the fingerprint is taken from.
    private static final long PUBLIC_BUILD_DATE_UTC = 1789730400L;
    private static final String PUBLIC_BUILD_DATE = "Fri Sep 18 11:20:00 UTC 2026";
    private static final String PUBLIC_BUILD_USER = "android-build";

    private static volatile boolean sActive;
    // Whole gigabytes added to free space, so apps reading it at the same moment disagree.
    private static volatile long sFreeOffset;
    // Per-app shifts of the live battery readings, so apps reading them together disagree.
    private static volatile int sBatteryLevelOffset;
    private static volatile int sBatteryVoltageOffset;
    private static volatile int sBatteryTemperatureOffset;

    private FingerprintDefender() {}

    /** Whether this app process runs with a masked profile. */
    public static boolean isActive() {
        return sActive;
    }

    /** Called once the app context exists, after the Pixel spoofing hooks. */
    public static void apply(Context context) {
        if (Process.isIsolated() || Process.myUid() < Process.FIRST_APPLICATION_UID) {
            return;
        }
        final String packageName = context.getPackageName();
        if (packageName == null) {
            return;
        }
        final long appSeed;
        try {
            // SettingsProvider only reveals the caller's own entry, with its seed, to apps.
            final String raw = Settings.Secure.getString(context.getContentResolver(),
                    KEY_PACKAGES);
            if (getEpoch(raw, packageName) < 0) {
                return;
            }
            appSeed = getAppSeed(raw);
        } catch (Throwable t) {
            Log.w(TAG, "Could not read the protected apps", t);
            return;
        }
        sFreeOffset = (appSeed % (2 * MAX_FREE_OFFSET_GB + 1) - MAX_FREE_OFFSET_GB) * GB;
        sBatteryLevelOffset = (int) ((appSeed >>> 8) % 31) - 15;
        sBatteryVoltageOffset = (int) ((appSeed >>> 16) % 31) * 10 - 150;
        sBatteryTemperatureOffset = (int) ((appSeed >>> 24) % 9) * 5 - 20;
        sActive = true;
        // Another spoof already gave this app a different device; masking its build as well
        // would contradict it, so only the device independent protection applies.
        if (!TextUtils.equals(Build.MODEL, SystemProperties.get("ro.product.model"))) {
            return;
        }
        SystemProperties.setOverrides(stockProps());
        // The fingerprint and build number already match a public build; the date and
        // origin would still single this build out.
        PixelPropsUtils.setPropValue("TIME", PUBLIC_BUILD_DATE_UTC * 1000);
        PixelPropsUtils.setPropValue("TAGS", "release-keys");
        PixelPropsUtils.setPropValue("TYPE", "user");
        PixelPropsUtils.setPropValue("USER", PUBLIC_BUILD_USER);
        PixelPropsUtils.setPropValue("HOST", SystemProperties.get("ro.build.host"));
    }

    /** Properties of a locked user build released with the public fingerprint. */
    private static Map<String, String> stockProps() {
        final Map<String, String> props = new ArrayMap<>();
        for (String partition : new String[] {"", "system.", "product.", "system_ext.",
                "vendor.", "odm."}) {
            props.put("ro." + partition + "build.tags", "release-keys");
            props.put("ro." + partition + "build.type", "user");
            props.put("ro." + partition + "build.date", PUBLIC_BUILD_DATE);
            props.put("ro." + partition + "build.date.utc", String.valueOf(PUBLIC_BUILD_DATE_UTC));
        }
        props.put("ro.build.user", PUBLIC_BUILD_USER);
        props.put("ro.debuggable", "0");
        props.put("ro.secure", "1");
        props.put("ro.adb.secure", "1");
        props.put("ro.boot.verifiedbootstate", "green");
        props.put("ro.boot.vbmeta.device_state", "locked");
        props.put("ro.boot.flash.locked", "1");
        props.put("ro.boot.veritymode", "enforcing");
        return props;
    }

    /**
     * Free space shifted by this app's own offset and rounded down to a whole gigabyte, kept
     * within the volume.
     */
    public static long coarseFree(long bytes, long total) {
        if (bytes <= 0) {
            return bytes;
        }
        final long shifted = Math.max(0, Math.min(total, bytes + sFreeOffset));
        return (shifted / GB) * GB;
    }

    /** Battery level in percent, shifted for this app and never below 20%. */
    public static int maskBatteryLevel(int percent) {
        if (!sActive || percent < 0) {
            return percent;
        }
        return Math.max(MIN_BATTERY_LEVEL, Math.min(100, percent + sBatteryLevelOffset));
    }

    /**
     * A copy of a battery broadcast with shifted live readings and no wear counters. Sticky
     * broadcasts are cached, so the original is never changed.
     */
    public static Intent maskBatteryIntent(Intent original) {
        if (!sActive || original == null
                || !Intent.ACTION_BATTERY_CHANGED.equals(original.getAction())) {
            return original;
        }
        final Intent intent = new Intent(original);
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) {
            intent.putExtra(BatteryManager.EXTRA_LEVEL,
                    maskBatteryLevel(level * 100 / scale) * scale / 100);
        }
        final int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        if (voltage > 0) {
            intent.putExtra(BatteryManager.EXTRA_VOLTAGE,
                    (voltage + sBatteryVoltageOffset) / 10 * 10);
        }
        if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            // Tenths of a degree, rounded to half a degree.
            final int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            intent.putExtra(BatteryManager.EXTRA_TEMPERATURE,
                    (temperature + sBatteryTemperatureOffset) / 5 * 5);
        }
        intent.removeExtra(BatteryManager.EXTRA_CHARGE_COUNTER);
        intent.removeExtra(BatteryManager.EXTRA_CYCLE_COUNT);
        return intent;
    }

    /**
     * The app's epoch from a {@link #KEY_PACKAGES} value, or -1 when it isn't protected. Entries
     * are {@code package:epoch}; the copy given to an app also carries {@code :seed}.
     */
    public static int getEpoch(String raw, String packageName) {
        if (TextUtils.isEmpty(raw) || packageName == null) {
            return -1;
        }
        for (String entry : raw.split(";")) {
            final String[] parts = entry.split(":");
            if (parts[0].equals(packageName)) {
                try {
                    return parts.length < 2 ? 0 : Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return -1;
    }

    /** The seed SettingsProvider appends to an app's own entry. */
    private static long getAppSeed(String raw) {
        final String[] parts = raw.split(";")[0].split(":");
        try {
            return parts.length < 3 ? 0 : Long.parseUnsignedLong(parts[2], 16) & Long.MAX_VALUE;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stable random value for one app and one purpose; differs between apps and epochs. */
    public static long seed(String salt, int userId, String packageName, int epoch,
            String purpose) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((salt + '|' + userId + '|' + packageName + '|' + epoch + '|' + purpose)
                    .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong() & Long.MAX_VALUE;
        } catch (Exception e) {
            return (salt + packageName + epoch + purpose).hashCode() & Integer.MAX_VALUE;
        }
    }
}
