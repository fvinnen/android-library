/* Copyright Airship and Contributors */

package com.urbanairship;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.util.Log;

import com.urbanairship.util.UAStringUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared logging wrapper for all Airship log entries.
 * This class serves to consolidate the tag and log level in a
 * single location.
 */
public class Logger {

    @NonNull
    static String DEFAULT_TAG = "UALib";

    /**
     * The current log tag.
     * Defaults to "UALib".
     */
    private static String tag = DEFAULT_TAG;

    /**
     * The current log level, as defined by <code>android.util.Log</code>.
     * Defaults to <code>android.util.Log.ERROR</code>.
     */
    private static int logLevel = Log.ERROR;

    /**
     * A list of listeners.
     */
    private static final List<LoggerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Default logger.
     */
    private static final LoggerListener defaultLogger = new LoggerListener() {
        @Override
        public void onLog(int priority, @Nullable Throwable throwable, @Nullable String message) {
            // Log directly if we do not have a throwable
            if (throwable == null) {
                if (priority == Log.ASSERT) {
                    Log.wtf(tag, message);
                } else {
                    Log.println(priority, tag, message);
                }
                return;
            }

            // Log using one of the provided log methods
            switch (priority) {
                case Log.INFO:
                    Log.i(tag, message, throwable);
                    break;
                case Log.DEBUG:
                    Log.d(tag, message, throwable);
                    break;
                case Log.VERBOSE:
                    Log.v(tag, message, throwable);
                    break;
                case Log.WARN:
                    Log.w(tag, message, throwable);
                    break;
                case Log.ERROR:
                    Log.e(tag, message, throwable);
                    break;
                case Log.ASSERT:
                    Log.wtf(tag, message, throwable);
                    break;
            }
        }
    };

    static {
        listeners.add(defaultLogger);
    }

    /**
     * Private, unused constructor
     */
    private Logger() {
    }

    /**
     * Sets the logger tag.
     *
     * @param tag The tag.
     */
    public static void setTag(@NonNull String tag) {
        Logger.tag = tag;
    }

    /**
     * Sets the log level.
     *
     * @param logLevel The log level.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void setLogLevel(int logLevel) {
        Logger.logLevel = logLevel;
    }

    /**
     * Gets the log level.
     *
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static int getLogLevel() {
        return logLevel;
    }

    /**
     * Disables Airship from using the standard {@link Log} for logging.
     */
    public static void disableDefaultLogger() {
        removeListener(defaultLogger);
    }

    /**
     * Adds a listener.
     *
     * Listener callbacks are synchronized but will be made from the originating thread.
     * Responsibility for any additional threading guarantees falls on the application.
     *
     * @param listener The listener.
     */
    public static void addListener(@NonNull LoggerListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener The listener.
     */
    public static void removeListener(@NonNull LoggerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Send a warning log message.
     *
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void warn(@NonNull String message, @Nullable Object... args) {
        log(Log.WARN, null, message, args);
    }

    /**
     * Send a warning log message.
     *
     * @param t An exception to log
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void warn(@NonNull Throwable t, @NonNull String message, @Nullable Object... args) {
        log(Log.WARN, t, message, args);
    }

    /**
     * Send a warning log message.
     *
     * @param t An exception to log
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void warn(@NonNull Throwable t) {
        log(Log.WARN, t, null);
    }

    /**
     * Send a verbose log message.
     *
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void verbose(@NonNull String message, @Nullable Object... args) {
        log(Log.VERBOSE, null, message, args);
    }

    /**
     * Send a debug log message.
     *
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void debug(@NonNull String message, @Nullable Object... args) {
        log(Log.DEBUG, null, message, args);
    }

    /**
     * Send a debug log message.
     *
     * @param t An exception to log
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void debug(@NonNull Throwable t, @NonNull String message, @Nullable Object... args) {
        log(Log.DEBUG, t, message, args);
    }

    /**
     * Send an info log message.
     *
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void info(@NonNull String message, @NonNull Object... args) {
        log(Log.INFO, null, message, args);
    }

    /**
     * Send an info log message.
     *
     * @param t An exception to log
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void info(@NonNull Throwable t, @NonNull String message, @Nullable Object... args) {
        log(Log.INFO, t, message, args);
    }

    /**
     * Send an error log message.
     *
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void error(@NonNull String message, @Nullable Object... args) {
        log(Log.ERROR, null, message, args);
    }

    /**
     * Send an error log message.
     *
     * @param t An exception to log
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void error(@NonNull Throwable t) {
        log(Log.ERROR, t, null);
    }

    /**
     * Send an error log message.
     *
     * @param t An exception to log
     * @param message The message you would like logged.
     * @param args The message args.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void error(@NonNull Throwable t, @NonNull String message, @Nullable Object... args) {
        log(Log.ERROR, t, message, args);
    }

    /**
     * Helper method that performs the logging.
     *
     * @param priority The log priority level.
     * @param throwable The optional exception.
     * @param message The optional message.
     * @param args The optional message args.
     */
    private static void log(int priority, @Nullable Throwable throwable, @Nullable String message, @Nullable Object... args) {
        if (logLevel > priority) {
            return;
        }

        if (message == null && throwable == null) {
            return;
        }

        String formattedMessage;

        if (UAStringUtil.isEmpty(message)) {
            // Default to empty string
            formattedMessage = "";
        } else {
            // Format the message if we have arguments
            formattedMessage = (args == null || args.length == 0) ? message : String.format(Locale.ROOT, message, args);
        }

        for (LoggerListener listener : listeners) {
            listener.onLog(priority, throwable, formattedMessage);
        }
    }

    /**
     * Parses the log level from a String.
     *
     * @param value The log level as a String.
     * @param defaultValue Default value if the value is empty.
     * @return The log level.
     * @throws IllegalArgumentException if an invalid log level is provided.
     */
    static int parseLogLevel(@Nullable String value, int defaultValue) throws IllegalArgumentException {
        if (UAStringUtil.isEmpty(value)) {
            return defaultValue;
        }

        switch (value.toUpperCase(Locale.ROOT)) {
            case "ASSERT":
            case "NONE":
                return Log.ASSERT;
            case "DEBUG":
                return Log.DEBUG;
            case "ERROR":
                return Log.ERROR;
            case "INFO":
                return Log.INFO;
            case "VERBOSE":
                return Log.VERBOSE;
            case "WARN":
                return Log.WARN;
        }

        try {
            int intValue = Integer.valueOf(value);
            if (intValue <= Log.ASSERT && intValue >= Log.VERBOSE) {
                return intValue;
            }

            Logger.warn("%s is not a valid log level. Falling back to %s.", intValue, defaultValue);
            return defaultValue;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Invalid log level: " + value);
        }
    }

}
