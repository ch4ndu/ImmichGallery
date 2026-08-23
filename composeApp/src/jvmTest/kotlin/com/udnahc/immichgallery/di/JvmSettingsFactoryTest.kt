package com.udnahc.immichgallery.di

import com.udnahc.immichgallery.data.repository.SERVER_SETTING_DESCRIPTORS
import com.udnahc.immichgallery.data.repository.ServerSettingValueType
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmSettingsFactoryTest {
    @Test
    fun absentLegacyStateCreatesAppNodeAndCompletionMarker() {
        val root = MemoryPreferences()
        val appNode = root.node(JVM_SETTINGS_NODE)

        val settings = createJvmSettings(root, appNode)

        assertTrue(appNode.getBoolean(JVM_SETTINGS_MIGRATION_MARKER, false))
        assertEquals(setOf(JVM_SETTINGS_MIGRATION_MARKER), settings.keys)
    }

    @Test
    fun completedMigrationDoesNotTouchLegacyOrDestinationState() {
        val root = MemoryPreferences()
        val appNode = root.node(JVM_SETTINGS_NODE)
        val ownedKey = SERVER_SETTING_DESCRIPTORS.first().key
        root.put(ownedKey, "legacy")
        appNode.put(ownedKey, "destination")
        appNode.putBoolean(JVM_SETTINGS_MIGRATION_MARKER, true)

        createJvmSettings(root, appNode)

        assertEquals("legacy", root.get(ownedKey, null))
        assertEquals("destination", appNode.get(ownedKey, null))
    }

    @Test
    fun partialMigrationPreservesDestinationCopiesEveryTypeAndOnlyRemovesOwnedRootKeys() {
        val root = MemoryPreferences()
        val appNode = root.node(JVM_SETTINGS_NODE)
        val sibling = root.node("unrelated/sibling")
        val stringKey = descriptor(ServerSettingValueType.STRING)
        val intKey = descriptor(ServerSettingValueType.INT)
        val floatKey = descriptor(ServerSettingValueType.FLOAT)
        val booleanKey = descriptor(ServerSettingValueType.BOOLEAN)
        root.put(stringKey, "legacy-string")
        root.putInt(intKey, 17)
        root.putFloat(floatKey, 2.5f)
        root.putBoolean(booleanKey, true)
        root.put("unknown-root-key", "keep")
        sibling.put("unknown-sibling-key", "keep-sibling")
        appNode.put(stringKey, "destination-wins")

        createJvmSettings(root, appNode)

        assertEquals("destination-wins", appNode.get(stringKey, null))
        assertEquals(17, appNode.getInt(intKey, -1))
        assertEquals(2.5f, appNode.getFloat(floatKey, -1f))
        assertTrue(appNode.getBoolean(booleanKey, false))
        assertFalse(root.keys().any { it in setOf(stringKey, intKey, floatKey, booleanKey) })
        assertEquals("keep", root.get("unknown-root-key", null))
        assertEquals("keep-sibling", sibling.get("unknown-sibling-key", null))
        assertTrue(appNode.getBoolean(JVM_SETTINGS_MIGRATION_MARKER, false))

        root.putInt(intKey, 99)
        createJvmSettings(root, appNode)

        assertEquals(17, appNode.getInt(intKey, -1))
        assertEquals(99, root.getInt(intKey, -1))
    }

    @Test
    fun destinationFlushFailureLeavesLegacyKeysRetryable() {
        val root = MemoryPreferences()
        val appNode = root.node(JVM_SETTINGS_NODE) as MemoryPreferences
        val ownedKey = descriptor(ServerSettingValueType.STRING)
        root.put(ownedKey, "legacy")
        appNode.failNextFlush = true

        assertFailsWith<BackingStoreException> {
            migrateLegacyJvmSettings(root, appNode)
        }

        assertEquals("legacy", root.get(ownedKey, null))
        assertFalse(appNode.getBoolean(JVM_SETTINGS_MIGRATION_MARKER, false))
    }

    private fun descriptor(type: ServerSettingValueType): String =
        SERVER_SETTING_DESCRIPTORS.first { it.type == type }.key
}

private class MemoryPreferences(
    parent: AbstractPreferences? = null,
    name: String = ""
) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, MemoryPreferences>()
    var failNextFlush: Boolean = false

    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear(); children.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences =
        children.getOrPut(name) { MemoryPreferences(this, name) }

    @Throws(BackingStoreException::class)
    override fun syncSpi() = Unit

    @Throws(BackingStoreException::class)
    override fun flushSpi() {
        if (failNextFlush) {
            failNextFlush = false
            throw BackingStoreException("flush failed")
        }
    }
}
