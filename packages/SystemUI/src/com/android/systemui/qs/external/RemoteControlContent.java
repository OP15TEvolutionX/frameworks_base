/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.external;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

/**
 * Optional app-drawn body of a custom tile's control dialog. When the control reply carries
 * {@link #KEY_REMOTE_VIEWS}, it replaces the standard switch and list; the title, subtitle and
 * Done button stay SystemUI's. Clicks inside reach the app through its own PendingIntents.
 */
final class RemoteControlContent {
    static final String KEY_REMOTE_VIEWS = "remote_views";
    private static final String TAG = "CustomTile";

    private RemoteControlContent() {}

    /**
     * Shows the app's views if the reply has them, or the standard controls otherwise.
     * Main thread only.
     *
     * @return true when the app's views are shown
     */
    static boolean bind(SystemUIDialog dialog, Bundle control) {
        RemoteViews views = control.getParcelable(KEY_REMOTE_VIEWS, RemoteViews.class);
        ViewGroup remote = dialog.requireViewById(R.id.qs_control_remote);
        boolean custom = views != null;
        if (custom) {
            try {
                remote.removeAllViews();
                remote.addView(views.apply(dialog.getContext(), remote));
            } catch (RuntimeException e) {
                // A broken layout from the app must not take the shade down.
                Log.e(TAG, "Could not apply the tile's RemoteViews", e);
                remote.removeAllViews();
                custom = false;
            }
        } else {
            remote.removeAllViews();
        }
        int standard = custom ? View.GONE : View.VISIBLE;
        remote.setVisibility(custom ? View.VISIBLE : View.GONE);
        dialog.requireViewById(R.id.qs_control_toggle_row).setVisibility(standard);
        dialog.requireViewById(R.id.qs_control_items).setVisibility(standard);
        return custom;
    }
}
