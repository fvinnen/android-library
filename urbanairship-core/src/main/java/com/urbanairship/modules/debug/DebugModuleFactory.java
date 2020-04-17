/* Copyright Airship and Contributors */

package com.urbanairship.modules.debug;

import android.content.Context;

import com.urbanairship.AirshipVersionInfo;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.modules.Module;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

/**
 * Debug module factory.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface DebugModuleFactory extends AirshipVersionInfo {
    Module build(@NonNull Context context,
                 @NonNull PreferenceDataStore dataStore);
}
