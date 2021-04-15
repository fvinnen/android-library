package com.urbanairship.accengage.notifications;

import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;

import com.urbanairship.AirshipConfigOptions;
import com.urbanairship.accengage.AccengageMessage;
import com.urbanairship.push.PushMessage;
import com.urbanairship.push.notifications.NotificationArguments;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.annotation.Config;

import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.mockito.Mockito.mock;

@Config(sdk = 28)
@RunWith(AndroidJUnit4.class)
public class AccengageNotificationExtenderTest {
    private AirshipConfigOptions mockConfig;

    @Before
    public void setup() {
        mockConfig = mock(AirshipConfigOptions.class);
    }
    @Test
    public void testExtend() {
        Application application = ApplicationProvider.getApplicationContext();
        Context context = Mockito.spy(application);

        Bundle extras = new Bundle();
        extras.putString("a4saccentcolor", "#FF0000");
        extras.putString("a4scategory", "general");
        extras.putString("a4scontent", "accengageContent");
        extras.putString("a4sbigcontenthtml", "bigAccengageContentHtml");
        extras.putString("a4stitle", "title test");
        extras.putString("a4sgroup", "general");
        extras.putString("a4sgroupsummary", "generalsummary");
        extras.putString("a4sbigtemplate", "BigTextStyle");
        extras.putInt("a4spriority", 3);
        extras.putBoolean("a4smultiplelines", true);

        PushMessage pushMessage = new PushMessage(extras);
        AccengageMessage message = AccengageMessage.fromAirshipPushMessage(pushMessage, mockConfig);

        // Setup the NotificationsExtender
        NotificationArguments.Builder naBuilder = NotificationArguments.newBuilder(pushMessage);
        NotificationArguments arguments = naBuilder
                .setNotificationId("",77)
                .setNotificationChannelId("id")
                .build();
        AccengageNotificationExtender accengageNotificationExtender = new AccengageNotificationExtender(context, message, arguments);

        // Setup the builder to extend
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "acc_channel_general")
                .setCategory(message.getAccengageCategory())
                .setGroup(message.getAccengageGroup())
                .setGroupSummary(message.getAccengageGroupSummary())
                .setPriority(message.getAccengagePriority())
                .setLights(0xFFFFFFFF, 1000, 3000)
                .setAutoCancel(true);

        builder = accengageNotificationExtender.extend(builder);

        Notification notification = builder.build();
        String notificationTitle = String.valueOf(notification.extras.getCharSequence("android.title"));
        String notificationContent = String.valueOf(notification.extras.getCharSequence("android.text"));
        String notificationBigContent = String.valueOf(notification.extras.getCharSequence("android.bigText"));
        String notificationTickerText = String.valueOf(notification.tickerText);

        Assert.assertEquals("title test", notificationTitle);
        Assert.assertEquals("accengageContent", notificationContent);
        Assert.assertEquals("accengageContent", notificationTickerText);
        Assert.assertEquals(Color.parseColor("#FF0000"), notification.color);

    }

}
