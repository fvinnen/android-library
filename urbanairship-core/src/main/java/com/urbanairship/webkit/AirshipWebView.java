/* Copyright Airship and Contributors */

package com.urbanairship.webkit;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Base64;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.urbanairship.Logger;
import com.urbanairship.R;
import com.urbanairship.UAirship;
import com.urbanairship.util.ManifestUtils;

import java.io.File;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A web view that sets settings appropriate for Airship content.
 */
public class AirshipWebView extends WebView {

    private WebViewClient webViewClient;

    private static final String CACHE_DIRECTORY = "urbanairship";

    private String currentClientAuthRequestUrl;

    /**
     * AirshipWebView Constructor
     *
     * @param context A Context object used to access application assets.
     */
    public AirshipWebView(@NonNull Context context) {
        this(context, null);
    }

    /**
     * AirshipWebView Constructor
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     */
    public AirshipWebView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.webViewStyle);
    }

    /**
     * AirshipWebView Constructor
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     */
    public AirshipWebView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (!isInEditMode()) {
            init(context, attrs, defStyle, 0);
        }
    }

    /**
     * AirshipWebView Constructor
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     * @param defResStyle A resource identifier of a style resource that supplies default values for
     * the view, used only if defStyle is 0 or cannot be found in the theme. Can be 0 to not
     * look for defaults.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public AirshipWebView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle, int defResStyle) {
        super(context, attrs, defStyle, defResStyle);

        if (!isInEditMode()) {
            init(context, attrs, defStyle, defResStyle);
        }
    }

    /**
     * Helper method that sets the default web view settings for Airship
     * and calls through to initializeView and populateCustomJavascriptInterfaces.
     *
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     * @param defResStyle A resource identifier of a style resource that supplies default values for
     * the view, used only if defStyle is 0 or cannot be found in the theme. Can be 0 to not
     * look for defaults.
     */
    @SuppressLint({ "NewApi", "SetJavaScriptEnabled" })
    private void init(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle, int defResStyle) {
        WebSettings settings = getSettings();

        settings.setAppCacheEnabled(true);
        settings.setAppCachePath(getCachePath());
        settings.setDomStorageEnabled(true);

        if (Build.VERSION.SDK_INT >= 21) {
            if (attrs != null) {
                TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.AirshipWebView, defStyle, defResStyle);
                try {
                    int mixedContentMode = a.getInteger(R.styleable.AirshipWebView_mixed_content_mode, WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                    settings.setMixedContentMode(mixedContentMode);
                } finally {
                    a.recycle();
                }
            }
        }

        settings.setAllowFileAccess(true);
        settings.setJavaScriptEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (ManifestUtils.shouldEnableLocalStorage()) {
            Logger.verbose("Application contains metadata to enable local storage");
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);

            if (Build.VERSION.SDK_INT < 19) {
                String dir = ManifestUtils.LOCAL_STORAGE_DATABASE_DIRECTORY;
                String path = UAirship.getApplicationContext().getDir(dir, Context.MODE_PRIVATE).getPath();
                //noinspection deprecation
                settings.setDatabasePath(path);
            }
        }

        initializeView();
        populateCustomJavascriptInterfaces();
    }

    /**
     * Initializes the web view with any default settings.
     * <p>
     * Called during create.
     */
    protected void initializeView() {
    }

    /**
     * Populate any custom javascript interfaces by calling
     * addJavascriptInterface(Object interface, String identifier) for each
     * custom interface.
     * <p>
     * Called after initializeView.
     */
    protected void populateCustomJavascriptInterfaces() {
    }

    @Override
    public void loadData(@NonNull String data, @Nullable String mimeType, @Nullable String encoding) {
        onPreLoad();
        super.loadData(data, mimeType, encoding);
    }

    @Override
    public void loadDataWithBaseURL(@Nullable String baseUrl, @NonNull String data, @Nullable String mimeType, @Nullable String encoding,
                                    @Nullable String historyUrl) {
        onPreLoad();
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    /**
     * Loads the given URL.
     *
     * @param url The URL of the resource to load.
     */
    public void loadUrl(@NonNull String url) {
        onPreLoad();
        super.loadUrl(url);
    }

    /**
     * Loads the given URL with the specified additional HTTP headers.
     *
     * @param url The URL to load.
     * @param additionalHttpHeaders The additional headers to be used in the HTTP request for
     * this URL.
     */
    public void loadUrl(@NonNull String url, @NonNull Map<String, String> additionalHttpHeaders) {
        onPreLoad();
        super.loadUrl(url, additionalHttpHeaders);
    }


    @Override
    public void setWebViewClient(@Nullable WebViewClient webViewClient) {
        if (webViewClient != null && !(webViewClient instanceof AirshipWebViewClient)) {
            Logger.warn("The web view client should extend AirshipWebViewClient to support Airship url overrides and triggering actions from.");
        }

        this.webViewClient = webViewClient;
        super.setWebViewClient(webViewClient);
    }

    /**
     * Called right before data or a URL is passed to the web view to be loaded.
     */
    @SuppressLint("NewApi")
    protected void onPreLoad() {
        if (getWebViewClientCompat() == null) {
            Logger.debug("No web view client set, setting a default AirshipWebViewClient for landing page view.");
            setWebViewClient(new AirshipWebViewClient());
        }

        // Clear the last set auth request
        if (currentClientAuthRequestUrl != null && getWebViewClientCompat() != null && getWebViewClientCompat() instanceof AirshipWebViewClient) {
            AirshipWebViewClient webViewClient = (AirshipWebViewClient) getWebViewClientCompat();
            webViewClient.removeAuthRequestCredentials(currentClientAuthRequestUrl);
            currentClientAuthRequestUrl = null;
        }
    }

    /**
     * Gets the web view client.
     *
     * @return The web view client.
     */
    @Nullable
    WebViewClient getWebViewClientCompat() {
        return webViewClient;
    }

    /**
     * Gets the cache directory path. Creates the directories if
     * it does not exist.
     *
     * @return The absolute path to the cache directory.
     */
    @NonNull
    private String getCachePath() {
        File cacheDirectory = new File(UAirship.getApplicationContext().getCacheDir(), CACHE_DIRECTORY);
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            Logger.error("Failed to create the web cache directory.");
        }

        return cacheDirectory.getAbsolutePath();
    }

    /**
     * Set the client authorization request.
     *
     * @param url The URL string.
     * @param username The auth user
     * @param password The auth password
     */
    protected void setClientAuthRequest(@Nullable String url, @NonNull String username, @NonNull String password) {
        currentClientAuthRequestUrl = url;

        if (getWebViewClientCompat() != null && getWebViewClientCompat() instanceof AirshipWebViewClient) {
            AirshipWebViewClient webViewClient = (AirshipWebViewClient) getWebViewClientCompat();

            String host = Uri.parse(url).getHost();
            if (host != null) {
                webViewClient.addAuthRequestCredentials(host, username, password);
            }
        }
    }

    /**
     * Creates a basic auth string.
     *
     * @param userName The user name.
     * @param password The password.
     * @return The basic auth string.
     */
    @NonNull
    protected String createBasicAuth(@NonNull String userName, @NonNull String password) {
        String credentials = userName + ":" + password;
        return "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
    }

}
