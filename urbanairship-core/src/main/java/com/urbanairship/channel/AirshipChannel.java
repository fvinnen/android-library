/* Copyright Airship and Contributors */

package com.urbanairship.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.urbanairship.AirshipComponent;
import com.urbanairship.AirshipComponentGroups;
import com.urbanairship.Logger;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.UAirship;
import com.urbanairship.config.AirshipRuntimeConfig;
import com.urbanairship.http.RequestException;
import com.urbanairship.http.Response;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.job.JobInfo;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonMap;
import com.urbanairship.json.JsonValue;
import com.urbanairship.locale.LocaleChangedListener;
import com.urbanairship.locale.LocaleManager;
import com.urbanairship.util.Clock;
import com.urbanairship.util.Network;
import com.urbanairship.util.UAStringUtil;

import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

/**
 * Airship channel access.
 */
public class AirshipChannel extends AirshipComponent {

    /**
     * Broadcast that is sent when a channel has been created.
     */
    @NonNull
    public static final String ACTION_CHANNEL_CREATED = "com.urbanairship.CHANNEL_CREATED";

    /**
     * Action to update channel registration.
     */
    private static final String ACTION_UPDATE_CHANNEL = "ACTION_UPDATE_CHANNEL";

    /**
     * Extra to force a full channel registration update.
     */
    private static final String EXTRA_FORCE_FULL_UPDATE = "EXTRA_FORCE_FULL_UPDATE";

    /**
     * Max time between channel registration updates.
     */
    private static final long CHANNEL_REREGISTRATION_INTERVAL_MS = 24 * 60 * 60 * 1000; //24H

    /**
     * The default tag group.
     */
    private final String DEFAULT_TAG_GROUP = "device";

    // PreferenceDataStore keys
    private static final String APID_KEY = "com.urbanairship.push.APID";
    private static final String CHANNEL_ID_KEY = "com.urbanairship.push.CHANNEL_ID";
    private static final String TAGS_KEY = "com.urbanairship.push.TAGS";
    private static final String LAST_REGISTRATION_TIME_KEY = "com.urbanairship.push.LAST_REGISTRATION_TIME";
    private static final String LAST_REGISTRATION_PAYLOAD_KEY = "com.urbanairship.push.LAST_REGISTRATION_PAYLOAD";
    private static final String ATTRIBUTE_DATASTORE_KEY = "com.urbanairship.push.ATTRIBUTE_DATA_STORE";
    private static final String TAG_GROUP_DATASTORE_KEY = "com.urbanairship.push.PENDING_TAG_GROUP_MUTATIONS";

    private final ChannelApiClient channelApiClient;

    private final JobDispatcher jobDispatcher;
    private final LocaleManager localeManager;
    private final Clock clock;

    private final List<AirshipChannelListener> airshipChannelListeners = new CopyOnWriteArrayList<>();
    private final List<ChannelRegistrationPayloadExtender> channelRegistrationPayloadExtenders = new CopyOnWriteArrayList<>();
    private final Object tagLock = new Object();

    private final TagGroupRegistrar tagGroupRegistrar;
    private final AttributeRegistrar attributeRegistrar;

    private final AirshipRuntimeConfig runtimeConfig;

    private boolean channelTagRegistrationEnabled = true;
    private boolean channelCreationDelayEnabled;

    /**
     * Channel registration extender.
     *
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public interface ChannelRegistrationPayloadExtender {

        /**
         * Extends the channel registration builder.
         *
         * @param builder The builder.
         * @return The extended builder.
         */
        @WorkerThread
        @NonNull
        ChannelRegistrationPayload.Builder extend(@NonNull ChannelRegistrationPayload.Builder builder);

    }

    /**
     * Default constructor.
     *
     * @param context The application context.
     * @param dataStore The preference data store.
     * @param runtimeConfig The runtime config.
     * @param localeManager Locale manager.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public AirshipChannel(@NonNull Context context,
                          @NonNull PreferenceDataStore dataStore,
                          @NonNull AirshipRuntimeConfig runtimeConfig,
                          @NonNull LocaleManager localeManager) {

        this(context, dataStore, runtimeConfig, localeManager,
                JobDispatcher.shared(context), Clock.DEFAULT_CLOCK,
                new ChannelApiClient(runtimeConfig),
                new AttributeRegistrar(AttributeApiClient.channelClient(runtimeConfig), new PendingAttributeMutationStore(dataStore, ATTRIBUTE_DATASTORE_KEY)),
                new TagGroupRegistrar(TagGroupApiClient.channelClient(runtimeConfig), new PendingTagGroupMutationStore(dataStore, TAG_GROUP_DATASTORE_KEY)));
    }

    @VisibleForTesting
    AirshipChannel(@NonNull Context context,
                   @NonNull PreferenceDataStore dataStore,
                   @NonNull AirshipRuntimeConfig runtimeConfig,
                   @NonNull LocaleManager localeManager,
                   @NonNull JobDispatcher jobDispatcher,
                   @NonNull Clock clock,
                   @NonNull ChannelApiClient channelApiClient,
                   @NonNull AttributeRegistrar attributeRegistrar,
                   @NonNull TagGroupRegistrar tagGroupRegistrar) {
        super(context, dataStore);

        this.runtimeConfig = runtimeConfig;
        this.localeManager = localeManager;
        this.jobDispatcher = jobDispatcher;
        this.channelApiClient = channelApiClient;
        this.attributeRegistrar = attributeRegistrar;
        this.tagGroupRegistrar = tagGroupRegistrar;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    protected void init() {
        super.init();
        tagGroupRegistrar.setId(getId(), false);
        attributeRegistrar.setId(getId(), false);

        if (Logger.getLogLevel() < Log.ASSERT && !UAStringUtil.isEmpty(getId())) {
            Log.d(UAirship.getAppName() + " Channel ID", getId());
        }

        channelCreationDelayEnabled = getId() == null && runtimeConfig.getConfigOptions().channelCreationDelayEnabled;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    protected void onAirshipReady(@NonNull UAirship airship) {
        super.onAirshipReady(airship);

        localeManager.addListener(new LocaleChangedListener() {
            @Override
            public void onLocaleChanged(@NonNull Locale locale) {
                dispatchUpdateJob();
            }
        });

        if (getId() != null || !channelCreationDelayEnabled) {
            dispatchUpdateJob();
        }
    }

    /**
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addChannelRegistrationPayloadExtender(@NonNull ChannelRegistrationPayloadExtender extender) {
        this.channelRegistrationPayloadExtenders.add(extender);
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @WorkerThread
    @JobInfo.JobResult
    @Override
    public int onPerformJob(@NonNull UAirship airship, @NonNull JobInfo jobInfo) {
        if (ACTION_UPDATE_CHANNEL.equals(jobInfo.getAction())) {
            String channelId = getId();

            if (channelId == null && channelCreationDelayEnabled) {
                Logger.debug("Channel registration is currently disabled.");
                return JobInfo.JOB_FINISHED;
            }

            JsonValue extraForceFullUpdate = jobInfo.getExtras().get(EXTRA_FORCE_FULL_UPDATE);
            boolean forceFullUpdate = extraForceFullUpdate != null && extraForceFullUpdate.getBoolean(false);

            return onUpdateChannel(forceFullUpdate);
        }

        return JobInfo.JOB_FINISHED;
    }

    /**
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @AirshipComponentGroups.Group
    public int getComponentGroup() {
        return AirshipComponentGroups.CHANNEL;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void onComponentEnableChange(boolean isEnabled) {
        if (isEnabled) {
            dispatchUpdateJob();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public void onUrlConfigUpdated() {
        // If the channel already exists, perform a full update. Otherwise create it.
        dispatchUpdateJob(true);
    }

    /**
     * Gets the channel identifier. This Id is created asynchronously, so initially it may be null.
     * To be notified when the channel is updated, add a listener with {@link #addChannelListener(AirshipChannelListener)}.
     *
     * @return The channel Id, or null if the Id is not yet created.
     */
    @Nullable
    public String getId() {
        return getDataStore().getString(CHANNEL_ID_KEY, null);
    }

    /**
     * Adds a channel listener.
     *
     * @param listener The listener.
     */
    public void addChannelListener(@NonNull AirshipChannelListener listener) {
        this.airshipChannelListeners.add(listener);
    }

    /**
     * Removes a channel listener.
     *
     * @param listener The listener.
     */
    public void removeChannelListener(@NonNull AirshipChannelListener listener) {
        this.airshipChannelListeners.remove(listener);
    }

    /**
     * Adds a tag group listener.
     *
     * @param listener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addTagGroupListener(@NonNull TagGroupListener listener) {
        this.tagGroupRegistrar.addTagGroupListener(listener);
    }

    /**
     * Adds an attribute listener.
     *
     * @param listener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addAttributeListener(@NonNull AttributeListener listener) {
        this.attributeRegistrar.addAttributeListener(listener);
    }

    /**
     * Edits channel Tags.
     *
     * @return A {@link TagEditor}
     */
    @NonNull
    public TagEditor editTags() {
        return new TagEditor() {
            @Override
            protected void onApply(boolean clear, @NonNull Set<String> tagsToAdd, @NonNull Set<String> tagsToRemove) {
                if (isDataCollectionEnabled()) {
                    synchronized (tagLock) {
                        Set<String> tags = clear ? new HashSet<String>() : getTags();
                        tags.addAll(tagsToAdd);
                        tags.removeAll(tagsToRemove);
                        setTags(tags);
                    }
                } else {
                    Logger.warn("AirshipChannel - Unable to apply tag edits when opted out of data collection.");
                }
            }
        };
    }

    /**
     * Edit the channel tag groups.
     *
     * @return A {@link TagGroupsEditor}.
     */
    @NonNull
    public TagGroupsEditor editTagGroups() {
        return new TagGroupsEditor() {
            @Override
            protected boolean allowTagGroupChange(@NonNull String tagGroup) {
                if (channelTagRegistrationEnabled && DEFAULT_TAG_GROUP.equals(tagGroup)) {
                    Logger.error("Unable to add tags to `%s` tag group when `channelTagRegistrationEnabled` is true.", DEFAULT_TAG_GROUP);
                    return false;
                }

                return true;
            }

            @Override
            protected void onApply(@NonNull List<TagGroupsMutation> collapsedMutations) {
                if (!isDataCollectionEnabled()) {
                    Logger.warn("AirshipChannel - Unable to apply tag group edits when opted out of data collection.");
                    return;
                }

                if (!collapsedMutations.isEmpty()) {
                    tagGroupRegistrar.addPendingMutations(collapsedMutations);
                    dispatchUpdateJob();
                }
            }
        };
    }

    /**
     * Edit the attributes associated with this channel.
     *
     * @return An {@link AttributeEditor}.
     */
    @NonNull
    public AttributeEditor editAttributes() {
        return new AttributeEditor(clock) {
            @Override
            protected void onApply(@NonNull List<AttributeMutation> mutations) {
                if (!isDataCollectionEnabled()) {
                    Logger.info("Ignore attributes, data opted out.");
                    return;
                }

                if (!mutations.isEmpty()) {
                    attributeRegistrar.addPendingMutations(mutations);
                    dispatchUpdateJob();
                }
            }
        };
    }

    /**
     * Set tags for the channel and update the server.
     * <p>
     * Tags should be URL-safe with a length greater than 0 and less than 127 characters. If your
     * tag includes whitespace or special characters, we recommend URL encoding the string.
     * <p>
     * To clear the current set of tags, pass an empty set to this method.
     *
     * @param tags A set of tag strings.
     */
    public void setTags(@NonNull Set<String> tags) {
        if (isDataCollectionEnabled()) {
            synchronized (tagLock) {
                Set<String> normalizedTags = TagUtils.normalizeTags(tags);
                getDataStore().put(TAGS_KEY, JsonValue.wrapOpt(normalizedTags));
            }

            dispatchUpdateJob();
        } else {
            Logger.warn("AirshipChannel - Unable to set tags when opted out of data collection.");
        }
    }

    /**
     * Returns the current set of tags.
     * <p>
     * An empty set indicates that no tags are set on this channel.
     *
     * @return The current set of tags.
     */
    @NonNull
    public Set<String> getTags() {
        synchronized (tagLock) {
            Set<String> tags = new HashSet<>();
            JsonValue jsonValue = getDataStore().getJsonValue(TAGS_KEY);

            if (jsonValue.isJsonList()) {
                for (JsonValue tag : jsonValue.optList()) {
                    if (tag.isString()) {
                        tags.add(tag.getString());
                    }
                }
            }

            Set<String> normalizedTags = TagUtils.normalizeTags(tags);

            // To prevent the getTags call from constantly logging tag set failures, sync tags
            if (tags.size() != normalizedTags.size()) {
                this.setTags(normalizedTags);
            }

            return normalizedTags;
        }
    }

    /**
     * Enables channel creation if channel creation has been delayed.
     * <p>
     * This setting is persisted between application starts, so there is no need to call this
     * repeatedly. It is only necessary to call this when channelCreationDelayEnabled has been
     * set to <code>true</code> in the airship config.
     */
    public void enableChannelCreation() {
        if (isChannelCreationDelayEnabled()) {
            channelCreationDelayEnabled = false;
            dispatchUpdateJob();
        }
    }

    /**
     * Updates registration.
     *
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void updateRegistration() {
        dispatchUpdateJob();
    }

    /**
     * Returns the payload for the next channel registration
     *
     * @return The ChannelRegistrationPayload payload
     */
    @WorkerThread
    @NonNull
    private ChannelRegistrationPayload getNextChannelRegistrationPayload() {
        boolean shouldSetTags = getChannelTagRegistrationEnabled();

        ChannelRegistrationPayload.Builder builder = new ChannelRegistrationPayload.Builder()
                .setTags(shouldSetTags, shouldSetTags ? getTags() : null)
                .setApid(getDataStore().getString(APID_KEY, null));

        switch (runtimeConfig.getPlatform()) {
            case UAirship.ANDROID_PLATFORM:
                builder.setDeviceType(ChannelRegistrationPayload.ANDROID_DEVICE_TYPE);
                break;
            case UAirship.AMAZON_PLATFORM:
                builder.setDeviceType(ChannelRegistrationPayload.AMAZON_DEVICE_TYPE);
                break;
        }

        builder.setTimezone(TimeZone.getDefault().getID());

        Locale locale = localeManager.getLocale();

        if (!UAStringUtil.isEmpty(locale.getCountry())) {
            builder.setCountry(locale.getCountry());
        }

        if (!UAStringUtil.isEmpty(locale.getLanguage())) {
            builder.setLanguage(locale.getLanguage());
        }

        if (UAirship.getPackageInfo() != null) {
            builder.setAppVersion(UAirship.getPackageInfo().versionName);
        }

        builder.setSdkVersion(UAirship.getVersion());

        if (isDataCollectionEnabled()) {
            builder.setCarrier(Network.getCarrier());
            builder.setDeviceModel(Build.MODEL);
            builder.setApiVersion(Build.VERSION.SDK_INT);
        }

        for (ChannelRegistrationPayloadExtender extender : channelRegistrationPayloadExtenders) {
            builder = extender.extend(builder);
        }

        return builder.build();
    }

    /**
     * Determines whether tags are enabled on the device.
     * If <code>false</code>, no locally specified tags will be sent to the server during registration.
     * The default value is <code>true</code>.
     *
     * @return <code>true</code> if tags are enabled on the device, <code>false</code> otherwise.
     */
    public boolean getChannelTagRegistrationEnabled() {
        return channelTagRegistrationEnabled;
    }

    /**
     * Sets whether tags are enabled on the device. The default value is <code>true</code>.
     * If <code>false</code>, no locally specified tags will be sent to the server during registration.
     *
     * @param enabled A boolean indicating whether tags are enabled on the device.
     */
    public void setChannelTagRegistrationEnabled(boolean enabled) {
        channelTagRegistrationEnabled = enabled;
    }

    /**
     * Gets any pending tag updates.
     *
     * @return The list of pending tag updates.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<TagGroupsMutation> getPendingTagUpdates() {
        return tagGroupRegistrar.getPendingMutations();
    }

    /**
     * Gets any pending attribute updates.
     *
     * @return The list of pending attribute updates.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<AttributeMutation> getPendingAttributeUpdates() {
        return attributeRegistrar.getPendingMutations();
    }

    /**
     * Determines whether channel creation is initially disabled, to be enabled later
     * by enableChannelCreation.
     *
     * @return <code>true</code> if channel creation is initially disabled, <code>false</code> otherwise.
     */
    boolean isChannelCreationDelayEnabled() {
        return channelCreationDelayEnabled;
    }

    /**
     * Check the specified payload and last registration time to determine if registration is required
     *
     * @param payload The channel registration payload
     * @return <code>True</code> if registration is required, <code>false</code> otherwise
     */
    private boolean shouldUpdateRegistration(@NonNull ChannelRegistrationPayload payload) {
        // check time and payload
        ChannelRegistrationPayload lastSuccessPayload = getLastRegistrationPayload();
        if (lastSuccessPayload == null) {
            Logger.verbose("Should update registration. Last payload is null.");
            return true;
        }

        long timeSinceLastRegistration = (System.currentTimeMillis() - getLastRegistrationTime());
        if (timeSinceLastRegistration >= CHANNEL_REREGISTRATION_INTERVAL_MS) {
            Logger.verbose("Should update registration. Time since last registration time is greater than 24 hours.");
            return true;
        }

        if (!payload.equals(lastSuccessPayload)) {
            Logger.verbose("Should update registration. Channel registration payload has changed.");
            return true;
        }

        return false;
    }

    /**
     * Sets the last registration payload and registration time. The last payload and registration
     * time are used to prevent duplicate channel updates.
     *
     * @param channelPayload A ChannelRegistrationPayload.
     */
    private void setLastRegistrationPayload(ChannelRegistrationPayload channelPayload) {
        getDataStore().put(LAST_REGISTRATION_PAYLOAD_KEY, channelPayload);
        getDataStore().put(LAST_REGISTRATION_TIME_KEY, System.currentTimeMillis());
    }

    /**
     * Gets the last registration payload
     *
     * @return a ChannelRegistrationPayload
     */
    @Nullable
    private ChannelRegistrationPayload getLastRegistrationPayload() {
        JsonValue jsonValue = getDataStore().getJsonValue(LAST_REGISTRATION_PAYLOAD_KEY);
        if (jsonValue.isNull()) {
            return null;
        }

        try {
            return ChannelRegistrationPayload.fromJson(jsonValue);
        } catch (JsonException e) {
            Logger.error(e, "AirshipChannel - Failed to parse payload from JSON.");
            return null;
        }
    }

    /**
     * Get the last registration time
     *
     * @return the last registration time
     */
    private long getLastRegistrationTime() {
        long lastRegistrationTime = getDataStore().getLong(LAST_REGISTRATION_TIME_KEY, 0L);

        // If its in the future reset it
        if (lastRegistrationTime > System.currentTimeMillis()) {
            Logger.verbose("Resetting last registration time.");
            getDataStore().put(LAST_REGISTRATION_TIME_KEY, 0);
            return 0;
        }

        return lastRegistrationTime;
    }

    /**
     * Called to create the channel.
     *
     * @return The job result.
     */
    @WorkerThread
    @JobInfo.JobResult
    private int onCreateChannel() {
        ChannelRegistrationPayload payload = getNextChannelRegistrationPayload();
        Response<String> response;
        try {
            response = channelApiClient.createChannelWithPayload(payload);
        } catch (RequestException e) {
            Logger.debug(e, "Channel registration failed, will retry");
            return JobInfo.JOB_RETRY;
        }

        // 2xx
        if (response.isSuccessful()) {
            String channelId = response.getResult();
            Logger.info("Airship channel created: %s", channelId);
            getDataStore().put(CHANNEL_ID_KEY, channelId);
            tagGroupRegistrar.setId(channelId, false);
            attributeRegistrar.setId(channelId, false);
            setLastRegistrationPayload(payload);
            for (AirshipChannelListener listener : airshipChannelListeners) {
                listener.onChannelCreated(channelId);
            }

            if (runtimeConfig.getConfigOptions().extendedBroadcastsEnabled) {
                // Send ChannelCreated intent for other plugins that depend on Airship
                Intent channelCreatedIntent = new Intent(ACTION_CHANNEL_CREATED)
                        .setPackage(UAirship.getPackageName())
                        .addCategory(UAirship.getPackageName());

                channelCreatedIntent.putExtra(UAirship.EXTRA_CHANNEL_ID_KEY, channelId);

                getContext().sendBroadcast(channelCreatedIntent);
            }

            return JobInfo.JOB_FINISHED;
        }

        // 429 || 5xx
        if (response.isServerError() || response.isTooManyRequestsError()) {
            Logger.debug("Channel registration failed with status: %s, will retry", response.getStatus());
            return JobInfo.JOB_RETRY;
        }

        Logger.debug("Channel registration failed with status: %s", response.getStatus());
        return JobInfo.JOB_FINISHED;
    }

    /**
     * Handles channel registration update job.
     *
     * @return The job result.
     */
    @WorkerThread
    @JobInfo.JobResult
    private int onUpdateChannel(boolean forceFullUpdate) {
        String channelId = getId();
        // Create or Update Channel Registration
        int result = channelId == null ? onCreateChannel() : updateChannelRegistration(channelId, forceFullUpdate);
        if (result != JobInfo.JOB_FINISHED) {
            return result;
        } else {
            channelId = getId();
            if (channelId != null) {
                // Update tag groups and attributes
                boolean attributeResult = attributeRegistrar.uploadPendingMutations();
                boolean tagResult = tagGroupRegistrar.uploadPendingMutations();
                if (!attributeResult || !tagResult) {
                    return JobInfo.JOB_RETRY;
                }
            }
        }
        return JobInfo.JOB_FINISHED;
    }

    /**
     * Handles Channel Registration update.
     *
     * @param channelId The channel ID.
     * @param forceFullUpdate {@code true} to perform a full update, {@code false} to minimize the update payload.
     * @return The job result.
     */
    @WorkerThread
    @JobInfo.JobResult
    private int updateChannelRegistration(@NonNull String channelId, boolean forceFullUpdate) {
        ChannelRegistrationPayload payload = getNextChannelRegistrationPayload();
        if (!shouldUpdateRegistration(payload)) {
            Logger.verbose("Channel already up to date.");
            return JobInfo.JOB_FINISHED;
        }

        Logger.verbose("Performing channel registration.");

        Response<Void> response;
        try {
            ChannelRegistrationPayload updatePayload = forceFullUpdate ?
                    payload : payload.minimizedPayload(getLastRegistrationPayload());
            response = channelApiClient.updateChannelWithPayload(channelId, updatePayload);
        } catch (RequestException e) {
            Logger.debug(e, "Channel registration failed, will retry");
            return JobInfo.JOB_RETRY;
        }

        // 2xx
        if (response.isSuccessful()) {
            Logger.info("Airship channel updated.");
            // Set non-minimized payload as the last sent version, for future comparison
            setLastRegistrationPayload(payload);
            for (AirshipChannelListener listener : airshipChannelListeners) {
                listener.onChannelUpdated(getId());
            }
            return JobInfo.JOB_FINISHED;
        }

        // 429 || 5xx
        if (response.isServerError() || response.isTooManyRequestsError()) {
            Logger.debug("Channel registration failed with status: %s, will retry", response.getStatus());
            return JobInfo.JOB_RETRY;
        }

        // 409
        if (response.getStatus() == HttpURLConnection.HTTP_CONFLICT) {
            Logger.debug("Channel registration failed with status: %s, will clear channel ID and create a new channel.", response.getStatus());
            setLastRegistrationPayload(null);
            getDataStore().remove(CHANNEL_ID_KEY);
            // Create Channel Registration
            return onCreateChannel();
        }

        Logger.debug("Channel registration failed with status: %s", response.getStatus());
        return JobInfo.JOB_FINISHED;
    }

    /**
     * Dispatches a job to update registration.
     */
    private void dispatchUpdateJob() {
        dispatchUpdateJob(false);
    }

    /**
     * Dispatches a job to update registration.
     *
     * @param forceFullUpdate {@code true} to perform a full update, {@code false} to minimize the update payload.
     */
    private void dispatchUpdateJob(boolean forceFullUpdate) {
        JobInfo jobInfo = JobInfo.newBuilder()
                                 .setAction(ACTION_UPDATE_CHANNEL)
                                 .setExtras(JsonMap.newBuilder()
                                                   .put(EXTRA_FORCE_FULL_UPDATE, forceFullUpdate)
                                                   .build())
                                 .setNetworkAccessRequired(true)
                                 .setAirshipComponent(AirshipChannel.class)
                                 .build();

        jobDispatcher.dispatch(jobInfo);
    }

    @Override
    protected void onDataCollectionEnabledChanged(boolean isDataCollectionEnabled) {
        if (!isDataCollectionEnabled) {
            synchronized (tagLock) {
                getDataStore().remove(TAGS_KEY);
            }
            tagGroupRegistrar.clearPendingMutations();
            attributeRegistrar.clearPendingMutations();
        }

        updateRegistration();
    }
}
