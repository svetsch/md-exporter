package io.mdexporter.settings;

import java.util.Set;

/** Key/value storage of the user preferences: Java preferences (Windows registry) or a properties file. */
public interface SettingsStore {

    /** Where the settings are kept. */
    SettingsLocation location();

    /** Human readable location (registry key or file path). */
    String description();

    String get(String key, String defaultValue);

    /** Stores a value; {@code null} removes the key. */
    void put(String key, String value);

    void remove(String key);

    Set<String> keys();

    /** Removes every key (and the backing file, if any). */
    void clear();

    /** Persists pending changes; file stores write through, so this is mostly a no-op. */
    void flush();

    default double getDouble(String key, double defaultValue) {
        String value = get(key, null);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    default void putDouble(String key, double value) {
        put(key, Double.toString(value));
    }

    default int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    default void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    default void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    /** Copies every key of this store into {@code target}. */
    default void copyTo(SettingsStore target) {
        for (String key : keys()) {
            target.put(key, get(key, null));
        }
        target.flush();
    }
}
