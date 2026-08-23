package com.udnahc.immichgallery.di

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.repository.SERVER_SETTING_DESCRIPTORS
import com.udnahc.immichgallery.data.repository.ServerSettingValueType
import java.util.prefs.Preferences

internal const val JVM_SETTINGS_NODE = "com/udnahc/immichgallery"
internal const val JVM_SETTINGS_MIGRATION_MARKER = "jvm_app_node_migration_complete"

internal fun createJvmSettings(
    legacyRoot: Preferences = Preferences.userRoot(),
    appNode: Preferences = legacyRoot.node(JVM_SETTINGS_NODE)
): Settings {
    migrateLegacyJvmSettings(legacyRoot, appNode)
    return PreferencesSettings(appNode)
}

internal fun migrateLegacyJvmSettings(
    legacyRoot: Preferences,
    appNode: Preferences
) {
    if (appNode.getBoolean(JVM_SETTINGS_MIGRATION_MARKER, false)) return
    val legacyKeys = legacyRoot.keys().toSet()
    val destinationKeys = appNode.keys().toSet()
    SERVER_SETTING_DESCRIPTORS.forEach { descriptor ->
        if (descriptor.key !in legacyKeys) return@forEach
        if (descriptor.key !in destinationKeys) {
            when (descriptor.type) {
                ServerSettingValueType.STRING ->
                    appNode.put(descriptor.key, legacyRoot.get(descriptor.key, ""))

                ServerSettingValueType.INT ->
                    appNode.putInt(descriptor.key, legacyRoot.getInt(descriptor.key, 0))

                ServerSettingValueType.FLOAT ->
                    appNode.putFloat(descriptor.key, legacyRoot.getFloat(descriptor.key, 0f))

                ServerSettingValueType.BOOLEAN ->
                    appNode.putBoolean(descriptor.key, legacyRoot.getBoolean(descriptor.key, false))
            }
        }
    }
    // Make every destination copy durable before deleting the only legacy
    // copy. A failed destination flush leaves the root untouched and retryable.
    appNode.flush()
    SERVER_SETTING_DESCRIPTORS.forEach { descriptor ->
        if (descriptor.key in legacyKeys) legacyRoot.remove(descriptor.key)
    }
    legacyRoot.flush()
    appNode.putBoolean(JVM_SETTINGS_MIGRATION_MARKER, true)
    appNode.flush()
}
