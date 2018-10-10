/* Copyright 2018 Urban Airship and Contributors */

package com.urbanairship.preference;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;

import com.urbanairship.UAirship;

/**
 * CheckboxPreference to enable/disable push notification sounds.
 */
public class SoundEnablePreference extends UACheckBoxPreference {

    private static final String CONTENT_DESCRIPTION = "SOUND_ENABLED";

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public SoundEnablePreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SoundEnablePreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SoundEnablePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean getInitialAirshipValue(@NonNull UAirship airship) {
        return airship.getPushManager().isSoundEnabled();
    }

    @Override
    protected void onApplyAirshipPreference(@NonNull UAirship airship, boolean enabled) {
        airship.getPushManager().setSoundEnabled(enabled);
    }

    @NonNull
    @Override
    protected String getContentDescription() {
        return CONTENT_DESCRIPTION;
    }
}
