/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy;

import android.annotation.DimenRes;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;

import com.android.internal.R;

/**
 * Utility functions for system bars used by both window manager and System UI.
 *
 * @hide
 */
public final class SystemBarUtils {

    /**
     * Gets the status bar height.
     */
    public static int getStatusBarHeight(Context context) {
        return getStatusBarHeight(context.getResources(), context.getDisplay().getCutout());
    }

    /**
     * Gets the status bar height with a specific display cutout.
     */
    public static int getStatusBarHeight(Resources res, DisplayCutout cutout) {
        final int defaultSize = getStatusBarDefaultHeight(res, cutout);
        final int safeInsetTop = cutout == null ? 0 : cutout.getSafeInsetTop();
        final int waterfallInsetTop = cutout == null ? 0 : cutout.getWaterfallInsets().top;
        // The status bar height should be:
        // Max(top cutout size, (status bar default height + waterfall top size))
        return Math.max(safeInsetTop, defaultSize + waterfallInsetTop);
    }

    /**
     * Gets the status bar height for a specific rotation.
     *
     * @param context the Context to reflect the display containing the display cutout. The size and
     *                the rotation will be used as a reference of the calculation.
     */
    public static int getStatusBarHeightForRotation(
            Context context, @Surface.Rotation int targetRot) {
        final Display display = context.getDisplay();
        final int rotation = display.getRotation();
        final DisplayCutout cutout = display.getCutout();
        Insets insets;
        Insets waterfallInsets;
        final int localWidth = context.getResources().getDisplayMetrics().widthPixels;
        final int localHeight = context.getResources().getDisplayMetrics().heightPixels;
        if (cutout == null) {
            insets = Insets.NONE;
            waterfallInsets = Insets.NONE;
        } else {
            DisplayCutout rotated =
                    cutout.getRotated(localWidth, localHeight, rotation, targetRot);
            insets = Insets.of(rotated.getSafeInsets());
            waterfallInsets = rotated.getWaterfallInsets();
        }
        final int defaultSize = getStatusBarDefaultHeight(context.getResources(), cutout);
        // The status bar height should be:
        // Max(top cutout size, (status bar default height + waterfall top size))
        return Math.max(insets.top, defaultSize + waterfallInsets.top);
    }

    private static int getStatusBarDefaultHeight(Resources res, DisplayCutout cutout) {
        final int height = res.getDimensionPixelSize(R.dimen.status_bar_height_default);
        final TypedValue value = new TypedValue();
        res.getValue(R.dimen.status_bar_height_default, value, true);
        // Pixel dimensions describe native panel geometry, just like the cutout path.
        // Density-based dimensions already follow the display density and must not be scaled again.
        if (value.type != TypedValue.TYPE_DIMENSION
                || value.getComplexUnit() != TypedValue.COMPLEX_UNIT_PX || cutout == null) {
            return height;
        }
        final float ratio = cutout.getCutoutPathParserInfo().getPhysicalPixelDisplaySizeRatio();
        return ratio > 0 && Float.isFinite(ratio) ? Math.round(height * ratio) : height;
    }

    /**
     * Gets the height of area above QQS where battery/time go in notification panel. The height
     * equals to status bar height if status bar height is bigger than the
     * {@link R.dimen#quick_qs_offset_height}.
     */
    public static int getQuickQsOffsetHeight(Context context) {
        final int defaultSize = context.getResources().getDimensionPixelSize(
                R.dimen.quick_qs_offset_height);
        final int statusBarHeight = getStatusBarHeight(context);
        // Equals to status bar height if status bar height is bigger.
        return Math.max(defaultSize, statusBarHeight);
    }

    /**
     * Gets the taskbar frame height.
     */
    public static int getTaskbarHeight(Resources res) {
        return res.getDimensionPixelSize(R.dimen.taskbar_frame_height);
    }

    /**
     * Gets the default app header height in desktop view in pixels.
     */
    public static int getDesktopViewAppHeaderHeightPx(@NonNull Context context) {
        return context.getResources().getDimensionPixelSize(getDesktopViewAppHeaderHeightId());
    }

    /**
     * Gets the dimen resource id of the default app header height in desktop view.
     */
    @DimenRes
    public static int getDesktopViewAppHeaderHeightId() {
        return R.dimen.desktop_view_default_large_header_height;
    }
}
