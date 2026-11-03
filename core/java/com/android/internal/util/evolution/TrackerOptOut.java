/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.evolution;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns off analytics and attribution SDKs in apps protected by Fingerprint Defender, through
 * the opt-out switch each SDK ships for GDPR. Unlike blocking its traffic, this stops the SDK
 * from collecting, storing and retrying events, and the app keeps working as designed.
 *
 * <p>Push, in-app messaging, crash reporting and remote config are left alone: apps depend on
 * them and they don't follow the user across apps.
 *
 * @hide
 */
public final class TrackerOptOut {
    private static final String TAG = "TrackerOptOut";

    private interface Switch {
        void apply(ClassLoader cl, Context context) throws Exception;
    }

    private static final class Tracker {
        final String name;
        final Switch off;

        Tracker(String name, Switch off) {
            this.name = name;
            this.off = off;
        }
    }

    private static final Tracker[] TRACKERS = {
        new Tracker("Firebase Analytics", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.google.firebase.analytics.FirebaseAnalytics",
                        "getInstance", ctx),
                "setAnalyticsCollectionEnabled", false)),
        new Tracker("Google Analytics", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.google.android.gms.analytics.GoogleAnalytics",
                        "getInstance", ctx),
                "setAppOptOut", true)),
        new Tracker("AppsFlyer", (cl, ctx) -> {
            Object lib = invokeStatic(cl, "com.appsflyer.AppsFlyerLib", "getInstance");
            invoke(lib, "anonymizeUser", true);
            invoke(lib, "stop", true, ctx);
        }),
        new Tracker("Adjust", (cl, ctx) -> {
            // Adjust 5 replaced setEnabled(false) with disable().
            try {
                invokeStatic(cl, "com.adjust.sdk.Adjust", "disable");
            } catch (NoSuchMethodException e) {
                invokeStatic(cl, "com.adjust.sdk.Adjust", "setEnabled", false);
            }
        }),
        new Tracker("AppMetrica", (cl, ctx) -> invokeStatic(cl,
                "io.appmetrica.analytics.AppMetrica", "setDataSendingEnabled", false)),
        new Tracker("Yandex Metrica", (cl, ctx) -> invokeStatic(cl,
                "com.yandex.metrica.YandexMetrica", "setStatisticsSending", ctx, false)),
        new Tracker("Facebook App Events", (cl, ctx) -> {
            String sdk = "com.facebook.FacebookSdk";
            invokeStatic(cl, sdk, "setAutoLogAppEventsEnabled", false);
            invokeStatic(cl, sdk, "setAdvertiserIDCollectionEnabled", false);
            invokeStatic(cl, sdk, "setLimitEventAndDataUsage", ctx, true);
        }),
        new Tracker("Amplitude", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.amplitude.api.Amplitude", "getInstance"),
                "setOptOut", true)),
        new Tracker("Mixpanel", (cl, ctx) -> {
            // Instances are keyed by the app's token, so opt out every one that exists.
            Class<?> processor = Class.forName(
                    "com.mixpanel.android.mpmetrics.MixpanelAPI$InstanceProcessor", false, cl);
            Object each = Proxy.newProxyInstance(cl, new Class<?>[] {processor},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "process" -> invoke(args[0], "optOutTracking");
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                        }
                        return null;
                    });
            Class.forName("com.mixpanel.android.mpmetrics.MixpanelAPI", true, cl)
                    .getMethod("allInstances", processor).invoke(null, each);
        }),
        new Tracker("Branch", (cl, ctx) -> invoke(
                invokeStatic(cl, "io.branch.referral.Branch", "getInstance"),
                "disableTracking", true)),
        new Tracker("Kochava", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.kochava.tracker.Tracker", "getInstance"),
                "setSleep", true)),
        new Tracker("Singular", (cl, ctx) -> invokeStatic(cl,
                "com.singular.sdk.Singular", "stopAllTracking")),
        new Tracker("Segment", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.segment.analytics.Analytics", "with", ctx),
                "optOut", true)),
        new Tracker("CleverTap", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.clevertap.android.sdk.CleverTapAPI",
                        "getDefaultInstance", ctx),
                "setOptOut", true)),
        new Tracker("mParticle", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.mparticle.MParticle", "getInstance"),
                "setOptOut", Boolean.TRUE)),
        new Tracker("Countly", (cl, ctx) -> {
            Object countly = invokeStatic(cl, "ly.count.android.sdk.Countly", "sharedInstance");
            try {
                invoke(invoke(countly, "consent"), "removeConsentAll");
            } catch (NoSuchMethodException e) {
                invoke(countly, "removeConsentAll");
            }
        }),
        new Tracker("App Center Analytics", (cl, ctx) -> invokeStatic(cl,
                "com.microsoft.appcenter.analytics.Analytics", "setEnabled", false)),
        new Tracker("Huawei Analytics", (cl, ctx) -> invoke(
                invokeStatic(cl, "com.huawei.hms.analytics.HiAnalytics", "getInstance", ctx),
                "setAnalyticsEnabled", false)),
        new Tracker("Airbridge", (cl, ctx) -> invokeStatic(cl,
                "co.ab180.airbridge.Airbridge", "stopTracking")),
        new Tracker("Smartlook", (cl, ctx) -> invoke(
                instance(cl, "com.smartlook.android.core.api.Smartlook"), "stop")),
        new Tracker("UXCam", (cl, ctx) -> invokeStatic(cl,
                "com.uxcam.UXCam", "optOutOverall")),
        new Tracker("FullStory", (cl, ctx) -> invokeStatic(cl,
                "com.fullstory.FS", "shutdown")),
        new Tracker("Contentsquare", (cl, ctx) -> invokeStatic(cl,
                "com.contentsquare.android.Contentsquare", "optOut", ctx)),
    };

    private static boolean sActivitySeen;

    private TrackerOptOut() {}

    /** Called after {@code Application.onCreate}, once SDKs initialized there exist. */
    public static void onApplicationCreated(Context context) {
        if (FingerprintDefender.isActive()) {
            apply(context);
        }
    }

    /** Called after the first activity's {@code onCreate} for SDKs that start lazily. */
    public static void onActivityCreated(Context context) {
        if (sActivitySeen || !FingerprintDefender.isActive()) {
            return;
        }
        sActivitySeen = true;
        apply(context);
    }

    private static void apply(Context context) {
        ClassLoader cl = context.getClassLoader();
        List<String> done = new ArrayList<>();
        for (Tracker tracker : TRACKERS) {
            try {
                tracker.off.apply(cl, context);
                done.add(tracker.name);
            } catch (ClassNotFoundException e) {
                // The app doesn't ship this SDK.
            } catch (Throwable t) {
                Log.d(TAG, "Could not opt out of " + tracker.name + ": " + t);
            }
        }
        if (!done.isEmpty()) {
            Log.i(TAG, context.getPackageName() + ": opted out of " + done);
        }
    }

    /** A singleton from a static getInstance(), or from a Kotlin companion without one. */
    private static Object instance(ClassLoader cl, String className) throws Exception {
        Class<?> c = Class.forName(className, true, cl);
        try {
            return c.getMethod("getInstance").invoke(null);
        } catch (NoSuchMethodException e) {
            Object companion = c.getField("Companion").get(null);
            return invoke(companion, "getInstance");
        }
    }

    private static Object invokeStatic(ClassLoader cl, String className, String name,
            Object... args) throws Exception {
        return call(Class.forName(className, true, cl), null, name, args);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        return call(target.getClass(), target, name, args);
    }

    /** Calls the public method whose parameters accept {@code args}. */
    private static Object call(Class<?> c, Object target, String name, Object[] args)
            throws Exception {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && accepts(m.getParameterTypes(), args)) {
                // The instance may come from a hidden implementation class.
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == boolean.class) {
                t = Boolean.class;
            }
            if (!t.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }
}
