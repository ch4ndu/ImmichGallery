package com.udnahc.immichgallery.domain.model

// 64-bit FNV-1a: stable across platforms and avoids persisting the full field string.
internal class OrderedFingerprintHasher {
    private var hash = FNV64_OFFSET_BASIS
    private var assetCount = 0

    fun addAsset(fields: List<String>) {
        if (assetCount > 0) mix(ASSET_SEPARATOR)
        fields.forEachIndexed { index, field ->
            if (index > 0) mix(FIELD_SEPARATOR)
            field.forEach(::mix)
        }
        assetCount++
    }

    fun result(): String = hash.toULong().toString(16).padStart(16, '0')

    private fun mix(char: Char) {
        hash = (hash xor char.code.toLong()) * FNV64_PRIME
    }

    private companion object {
        const val FNV64_OFFSET_BASIS = -0x340d631b7bdddcdbL
        const val FNV64_PRIME = 0x100000001b3L
        const val ASSET_SEPARATOR = '\n'
        const val FIELD_SEPARATOR = '\u001f'
    }
}
