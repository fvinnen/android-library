/* Copyright Airship and Contributors */

package com.urbanairship.remotedata;

import android.os.Looper;
import android.support.annotation.NonNull;

import com.urbanairship.BaseTestCase;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.TestActivityMonitor;
import com.urbanairship.TestAirshipRuntimeConfig;
import com.urbanairship.TestApplication;
import com.urbanairship.TestClock;
import com.urbanairship.UAirship;
import com.urbanairship.http.RequestException;
import com.urbanairship.http.Response;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.job.JobInfo;
import com.urbanairship.json.JsonList;
import com.urbanairship.json.JsonMap;
import com.urbanairship.json.JsonValue;
import com.urbanairship.locale.LocaleManager;
import com.urbanairship.push.PushListener;
import com.urbanairship.push.PushManager;
import com.urbanairship.push.PushMessage;
import com.urbanairship.reactive.Observable;
import com.urbanairship.reactive.Subscriber;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static junit.framework.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class RemoteDataTest extends BaseTestCase {

    private RemoteData remoteData;
    private PreferenceDataStore preferenceDataStore;
    private JobDispatcher mockDispatcher;
    private TestActivityMonitor activityMonitor;
    private LocaleManager localeManager;
    private PushManager pushManager;
    private PushListener pushListener;
    private TestClock clock;
    private RemoteDataApiClient mockClient;
    private RemoteDataPayload payload;
    private RemoteDataPayload otherPayload;
    private RemoteDataPayload emptyPayload;

    @Before
    public void setup() throws MalformedURLException {
        activityMonitor = new TestActivityMonitor();

        mockDispatcher = mock(JobDispatcher.class);
        preferenceDataStore = TestApplication.getApplication().preferenceDataStore;

        localeManager = new LocaleManager(getApplication(), preferenceDataStore);

        pushManager = mock(PushManager.class);

        clock = new TestClock();

        mockClient = mock(RemoteDataApiClient.class);
        when(mockClient.getRemoteDataUrl(any(Locale.class))).thenReturn(new URL("https://airship.com"));

        remoteData = new RemoteData(TestApplication.getApplication(), preferenceDataStore, TestAirshipRuntimeConfig.newTestConfig(),
                activityMonitor, mockDispatcher, localeManager, pushManager, clock, mockClient);

        ArgumentCaptor<PushListener> pushListenerArgumentCaptor = ArgumentCaptor.forClass(PushListener.class);
        remoteData.init();
        verify(pushManager).addInternalPushListener(pushListenerArgumentCaptor.capture());
        pushListener = pushListenerArgumentCaptor.getValue();

        payload = RemoteDataPayload.newBuilder()
                                   .setType("type")
                                   .setTimeStamp(123)
                                   .setData(JsonMap.newBuilder()
                                                   .put("foo", "bar")
                                                   .build())
                                   .build();
        otherPayload = RemoteDataPayload.newBuilder()
                                        .setType("otherType")
                                        .setTimeStamp(234)
                                        .setData(JsonMap.newBuilder()
                                                        .put("baz", "boz")
                                                        .build())
                                        .build();
        emptyPayload = RemoteDataPayload.emptyPayload("type");
    }

    @After
    public void teardown() {
        remoteData.tearDown();
    }

    @Test
    public void testOnPushReceived() {
        clearInvocations(mockDispatcher);

        Map<String, String> pushData = new HashMap<>();
        pushData.put(PushMessage.REMOTE_DATA_UPDATE_KEY, "remoteDataUpdate");
        PushMessage message = new PushMessage(pushData);

        pushListener.onPushReceived(message, true);

        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));

        verifyNoMoreInteractions(mockDispatcher);
    }

    /**
     * Test that a transition to foreground results in a refresh.
     */
    @Test
    public void testForegroundTransition() {
        clearInvocations(mockDispatcher);
        activityMonitor.foreground();

        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));

        verifyNoMoreInteractions(mockDispatcher);
    }

    /**
     * Test that a locale change results in a refresh.
     */
    @Test
    public void testLocaleChangeRefresh() {
        activityMonitor.foreground();
        clearInvocations(mockDispatcher);

        localeManager.setLocaleOverride(new Locale("de"));

        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));

        verifyNoMoreInteractions(mockDispatcher);
    }

    /**
     * Test that a url config update results in a refresh.
     */
    @Test
    public void testUrlConfigUpdateRefresh() {
        clearInvocations(mockDispatcher);

        remoteData.onUrlConfigUpdated();

        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));

        verifyNoMoreInteractions(mockDispatcher);
    }

    /**
     * Test a foreground transition will not trigger a refresh if its before the foreground refresh
     * interval.
     */
    @Test
    public void testRefreshInterval() throws MalformedURLException, RequestException {
        // Refresh
        clock.currentTimeMillis = 100;

        updatePayloads();

        // Set foreground refresh interval to 10 ms
        remoteData.setForegroundRefreshInterval(10);

        // Time travel 9 ms, should skip refresh on foreground
        clock.currentTimeMillis += 9;
        activityMonitor.foreground();
        verify(mockDispatcher, never()).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));

        // Time travel 1 ms, should refresh on foreground
        clock.currentTimeMillis += 1;
        activityMonitor.foreground();
        verify(mockDispatcher, times(1)).dispatch(Mockito.argThat(new ArgumentMatcher<JobInfo>() {
            @Override
            public boolean matches(JobInfo jobInfo) {
                return jobInfo.getAction().equals(RemoteData.ACTION_REFRESH);
            }
        }));
    }

    /**
     * Test that an empty cache will result in empty model objects in the initial callback.
     */
    @Test
    public void testPayloadsForTypeWithEmptyCache() throws MalformedURLException, RequestException {
        final List<RemoteDataPayload> subscribedPayloads = new ArrayList<>();

        Observable<RemoteDataPayload> payloadsObservable = remoteData.payloadsForType("type");

        payloadsObservable.subscribe(new Subscriber<RemoteDataPayload>() {
            @Override
            public void onNext(@NonNull RemoteDataPayload value) {
                subscribedPayloads.add(value);
            }
        });

        runLooperTasks();

        // The first callback should be an empty placeholder
        assertEquals(1, subscribedPayloads.size());
        assertEquals(RemoteDataPayload.emptyPayload("type"), subscribedPayloads.get(0));

        subscribedPayloads.clear();

        updatePayloads(payload);

        // The second callback should be the refreshed data
        assertEquals(1, subscribedPayloads.size());
        assertEquals(payload, subscribedPayloads.get(0));
    }

    /**
     * Test that a type missing in the response will result in an empty model object in the callback.
     */
    @Test
    public void testPayloadsForTypeWithMissingTypeInResponse() throws MalformedURLException, RequestException {
        updatePayloads(payload);

        final List<RemoteDataPayload> subscribedPayloads = new ArrayList<>();

        Observable<RemoteDataPayload> payloadsObservable = remoteData.payloadsForType("type");

        payloadsObservable.subscribe(new Subscriber<RemoteDataPayload>() {
            @Override
            public void onNext(@NonNull RemoteDataPayload value) {
                subscribedPayloads.add(value);
            }
        });

        runLooperTasks();
        subscribedPayloads.clear();

        updatePayloads(otherPayload);
        runLooperTasks();

        assertEquals(1, subscribedPayloads.size());
        assertEquals(emptyPayload, subscribedPayloads.get(0));
    }

    /**
     * Test that a single type will only produce a callback if the payload has changed
     */
    @Test
    public void testPayloadsForTypeDistinctness() throws MalformedURLException, RequestException {
        final List<RemoteDataPayload> subscribedPayloads = new ArrayList<>();
        Observable<RemoteDataPayload> payloadsObservable = remoteData.payloadsForType("type");
        payloadsObservable.subscribe(new Subscriber<RemoteDataPayload>() {
            @Override
            public void onNext(@NonNull RemoteDataPayload value) {
                subscribedPayloads.add(value);
            }
        });

        runLooperTasks();
        subscribedPayloads.clear();

        updatePayloads(payload);

        assertEquals(1, subscribedPayloads.size(), 1);
        subscribedPayloads.clear();

        updatePayloads(payload);
        assertEquals(0, subscribedPayloads.size());
    }

    /**
     * Test that a type missing in the response will result in empty model objects in the callback.
     */
    @Test
    public void testPayloadsForTypesWithMissingTypeInResponse() throws Exception {

        updatePayloads(payload, otherPayload);
        runLooperTasks();

        final List<Collection<RemoteDataPayload>> subscribedPayloads = new ArrayList<>();
        Observable<Collection<RemoteDataPayload>> payloadsObservable = remoteData.payloadsForTypes(Arrays.asList("type", "otherType"));

        payloadsObservable.subscribe(new Subscriber<Collection<RemoteDataPayload>>() {
            @Override
            public void onNext(@NonNull Collection<RemoteDataPayload> value) {
                subscribedPayloads.add(value);
            }
        });

        runLooperTasks();

        assertEquals(subscribedPayloads.size(), 1);
        assertEquals(asSet(payload, otherPayload), subscribedPayloads.get(0));

        subscribedPayloads.clear();

        // Remove the payload
        updatePayloads(otherPayload);

        assertEquals(1, subscribedPayloads.size());
        assertEquals(asSet(otherPayload, emptyPayload), subscribedPayloads.get(0));
    }

    /**
     * Test that a multiple types will only produce a callback if at least one of the payloads has changed
     */
    @Test
    public void testPayloadsForTypesDistinctness() throws MalformedURLException, RequestException {
        final List<Collection<RemoteDataPayload>> subscribedPayloads = new ArrayList<>();

        Observable<Collection<RemoteDataPayload>> payloadsObservable = remoteData.payloadsForTypes(Arrays.asList("type", "otherType"));
        payloadsObservable.subscribe(new Subscriber<Collection<RemoteDataPayload>>() {
            @Override
            public void onNext(@NonNull Collection<RemoteDataPayload> value) {
                subscribedPayloads.add(value);
            }
        });

        runLooperTasks();

        // Clear the first callback
        subscribedPayloads.clear();

        updatePayloads(payload, otherPayload);
        assertEquals(asSet(payload, otherPayload), subscribedPayloads.get(0));

        // Replaying the response should not result in another callback because the timestamp hasn't changed
        updatePayloads(payload, otherPayload);
        assertEquals(1, subscribedPayloads.size());

        subscribedPayloads.clear();

        // Sending a fresh payload with an updated timestamp should result in a new callback
        RemoteDataPayload freshPayload = RemoteDataPayload.newBuilder()
                                                          .setType(payload.getType())
                                                          .setTimeStamp(payload.getTimestamp() + 100000)
                                                          .setData(payload.getData())
                                                          .build();

        updatePayloads(freshPayload, otherPayload);
        assertEquals(asSet(freshPayload, otherPayload), subscribedPayloads.get(0));
    }

    @Test
    public void testLastModified() throws MalformedURLException, RequestException {
        Locale locale = Locale.forLanguageTag("en-US");
        localeManager.setLocaleOverride(locale);

        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Last-Modified", Collections.singletonList("lastModifiedResponse"));

        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(200)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.com"), asSet(payload)))
                .setResponseHeaders(headers)
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), eq(locale), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
        runLooperTasks();

        // Perform the update
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
        runLooperTasks();

        verify(mockClient).fetchRemoteDataPayloads(eq("lastModifiedResponse"), eq(locale), any(RemoteDataApiClient.PayloadParser.class));
    }

    /**
     * Test last modified is ignored if the metadata changes.
     */
    @Test
    public void testLastModifiedMetadataChanges() throws RequestException, MalformedURLException {
        Locale locale = Locale.forLanguageTag("en-US");
        Locale otherLocale = Locale.forLanguageTag("de-de");
        when(mockClient.getRemoteDataUrl(otherLocale)).thenReturn(new URL("https://airship.com/some-locale"));
        when(mockClient.getRemoteDataUrl(locale)).thenReturn(new URL("https://airship.com/some-locale"));

        localeManager.setLocaleOverride(locale);

        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Last-Modified", Collections.singletonList("lastModifiedResponse"));

        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(200)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.COM"), asSet(payload)))
                .setResponseHeaders(headers)
                .build();

        when(mockClient.fetchRemoteDataPayloads(eq((String) null), eq(locale), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
        runLooperTasks();

        localeManager.setLocaleOverride(otherLocale);

        when(mockClient.fetchRemoteDataPayloads(eq((String) null), eq(otherLocale), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
        runLooperTasks();
    }


    /**
     * Test parsing remote-data responses.
     */
    @Test
    public void testParseRemoteDataResponse() throws RequestException, MalformedURLException {
        ArgumentCaptor<RemoteDataApiClient.PayloadParser> parserArgumentCaptor = ArgumentCaptor.forClass(RemoteDataApiClient.PayloadParser.class);

        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(304)
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        remoteData.onPerformJob(UAirship.shared(), jobInfo);

        verify(mockClient).fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), parserArgumentCaptor.capture());

        RemoteDataApiClient.PayloadParser parser = parserArgumentCaptor.getValue();

        JsonValue payload = JsonMap.newBuilder()
                                   .put("type", "test")
                                   .put("timestamp", "2017-01-01T12:00:00")
                                   .put("data", JsonMap.newBuilder().put("foo", "bar").build())
                                   .build()
                                   .toJsonValue();

        JsonList payloads = new JsonList(Collections.singletonList(payload));
        URL url = new URL("http://some-url.com");
        Set<RemoteDataPayload> parsed = parser.parse(url, payloads);

        JsonMap metadata = JsonMap.newBuilder().put("url", url.toString()).build();
        assertEquals(RemoteDataPayload.parsePayloads(payloads, metadata), parsed);
    }


    /**
     * Test that fetching remote data succeeds if the status is 200
     */
    @Test
    public void testRefreshRemoteData200() throws RequestException, MalformedURLException {
        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(200)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.com"), asSet(payload)))
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
    }

    /**
     * Test that fetching remote data succeeds if the status is 304
     */
    @Test
    public void testRefreshRemoteData304() throws RequestException, MalformedURLException {
        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(304)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.com"), asSet(payload)))
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
    }

    /**
     * Test that fetching remote data job retries if the status is 5xx
     */
    @Test
    public void testRefreshRemoteDataServerError() throws RequestException, MalformedURLException {
        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(500)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.com"), asSet(payload)))
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_RETRY, remoteData.onPerformJob(UAirship.shared(), jobInfo));
    }

    /**
     * Test that fetching remote data job finishes if the status is 4xx
     */
    @Test
    public void testRefreshRemoteDataClientError() throws RequestException, MalformedURLException {
        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(400)
                .setResult(new RemoteDataApiClient.Result(new URL("https://airship.com"), asSet(payload)))
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));
    }

    private void updatePayloads(RemoteDataPayload... payloads) throws RequestException {
        Response<RemoteDataApiClient.Result> response = new Response.Builder<RemoteDataApiClient.Result>(200)
                .setResult(new RemoteDataApiClient.Result(mockClient.getRemoteDataUrl(localeManager.getLocale()), asSet(payloads)))
                .build();

        when(mockClient.fetchRemoteDataPayloads(nullable(String.class), any(Locale.class), any(RemoteDataApiClient.PayloadParser.class))).thenReturn(response);

        // Perform the update
        JobInfo jobInfo = JobInfo.newBuilder().setAction(RemoteData.ACTION_REFRESH).build();
        assertEquals(JobInfo.JOB_FINISHED, remoteData.onPerformJob(UAirship.shared(), jobInfo));

        runLooperTasks();
    }

    /**
     * Helper method to run all the looper tasks.
     */
    private void runLooperTasks() {
        ShadowLooper mainLooper = Shadows.shadowOf(Looper.getMainLooper());
        ShadowLooper backgroundLooper = Shadows.shadowOf(remoteData.backgroundThread.getLooper());

        do {
            mainLooper.runToEndOfTasks();
            backgroundLooper.runToEndOfTasks();
        }
        while (mainLooper.getScheduler().areAnyRunnable() || backgroundLooper.getScheduler().areAnyRunnable());
    }

    private static <T> Set<T> asSet(T... items) {
        return new HashSet<>(Arrays.asList(items));
    }

}
