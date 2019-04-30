/* Copyright Airship and Contributors */

package com.urbanairship.push.fcm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.support.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.urbanairship.PendingResult;
import com.urbanairship.push.PushMessage;
import com.urbanairship.push.PushProviderBridge;

import java.util.concurrent.Future;

/**
 * Airship FirebaseMessagingService.
 */
public class AirshipFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    @SuppressLint("UnknownNullness")
    public void onMessageReceived(RemoteMessage message) {
        processMessageSync(getApplicationContext(), message);
    }

    @Override
    @SuppressLint("UnknownNullness")
    public void onNewToken(String token) {
        processNewToken(getApplicationContext());
    }

    /**
     * Called to handle {@link #onMessageReceived(RemoteMessage)}. The task should be finished
     * before `onMessageReceived(RemoteMessage message)` is complete. Wait for the message to be complete
     * by calling `get()` on the future.
     *
     * @param context The application context.
     * @param message The message.
     * @return A future.
     */
    @NonNull
    public static Future<Void> processMessage(@NonNull Context context, @NonNull RemoteMessage message) {
        final PendingResult<Void> pendingResult = new PendingResult<>();
        PushProviderBridge.processPush(FcmPushProvider.class, new PushMessage(message.getData()))
                          .execute(context, new Runnable() {
                              @Override
                              public void run() {
                                  pendingResult.setResult(null);
                              }
                          });

        return pendingResult;
    }

    /**
     * Called to handle {@link #onMessageReceived(RemoteMessage)} synchronously.
     *
     * @param context The application context.
     * @param message The message.
     */
    public static void processMessageSync(@NonNull Context context, @NonNull RemoteMessage message) {
        PushProviderBridge.processPush(FcmPushProvider.class, new PushMessage(message.getData()))
                          .executeSync(context);
    }

    /**
     * Called to handle new tokens.
     *
     * @param context The application context.
     */
    public static void processNewToken(@NonNull Context context) {
        PushProviderBridge.requestRegistrationUpdate(context);
    }

}
