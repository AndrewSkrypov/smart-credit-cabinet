package ru.creditcabinet.user.domain

import java.util.Base64

/**
 * A profile picture: one of the cabinet's preset illustrations or a small photo the person uploaded.
 * Photos arrive already cropped and scaled by the browser; only raster formats are accepted, never SVG.
 */
object Avatar {
    /** Preset codes the cabinet draws itself; the list mirrors frontend/src/shared/avatars.ts. */
    val PRESETS = setOf(
        "briefcase", "chart", "coin", "rocket", "factory", "truck",
        "cart", "house", "gem", "handshake", "shield", "bank",
    )
    const val MAX_IMAGE_BYTES = 64 * 1024
    private const val PRESET = "preset:"
    private val DATA_URL = Regex("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$")

    /** Returns the stored form, or throws with a message for the person. */
    fun normalize(value: String?): String? {
        val avatar = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (avatar.startsWith(PRESET)) {
            require(avatar.removePrefix(PRESET) in PRESETS) { "Такой картинки для аватара нет" }
            return avatar
        }
        val match = requireNotNull(DATA_URL.matchEntire(avatar)) { "Аватар принимается в PNG, JPEG или WebP" }
        val bytes = try {
            Base64.getDecoder().decode(match.groupValues[2])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Файл аватара повреждён")
        }
        require(bytes.size <= MAX_IMAGE_BYTES) { "Фото слишком большое — уменьшите его до 64 КБ" }
        require(matchesFormat(match.groupValues[1], bytes)) { "Содержимое файла не совпадает с его форматом" }
        return avatar
    }

    private fun matchesFormat(format: String, bytes: ByteArray): Boolean {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
        return when (format) {
            "png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "jpeg" -> starts(0xff, 0xd8, 0xff)
            "webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            else -> false
        }
    }
}
