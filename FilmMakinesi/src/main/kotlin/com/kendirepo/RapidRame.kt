package com.kendirepo

import android.util.Base64

/**
 * FilmMakinesi / HDFilmCehennemi "CloseLoad" ve "Rapidrame" oynaticilarinin
 * ortak sifre cozucusu.
 *
 * Sayfadaki JS, kaynak adresini duz metin olarak tutmaz; sunucu tarafinda
 * uretilen parcalanmis bir diziyi (chunk) ve bir anahtar parcayi kullanarak
 * calisma zamaninda cozer. Bu nesne, o JS fonksiyonunun birebir Kotlin
 * karsiligidir.
 */
object RapidRame {

    fun decrypt(payload: String, separator: String = "^", saltHost: String? = null): String? {
        return runCatching { decryptInternal(payload, separator, saltHost) }.getOrNull()
    }

    private fun decryptInternal(payload: String, separator: String, saltHost: String?): String {
        val chunks = payload.split(separator).toMutableList()
        require(chunks.size > 10) { "payload too short" }

        val length = chunks.size - 2
        val keyIndex = 8 + (length % 5)
        val saltIndex = length % 7
        require(keyIndex < chunks.size && saltIndex < chunks.size) { "bad indexes" }

        val key = chunks.removeAt(keyIndex)
        var salt = chunks.removeAt(saltIndex)
        if (saltHost != null) salt += saltHost

        var value = chunks.joinToString("")
        if (salt.length > 4096) value = atob(value)

        var a = 0
        var b = 0
        salt.forEachIndexed { index, ch ->
            val code = ch.code
            a = (a * 37 + code) % 241
            b = (b + ((code shl 1) xor index)) and 255
        }

        val xorSeed = (a * 3 + b) % 256
        val xorStep = (b % 11) + 5
        var permutationSeed = ((b * 251 + a) % 65519) + 1

        // Anahtar parca; '7' base64, '3' ters cevirme, digerleri harf kaydirma.
        for (index in key.indices.reversed()) {
            when (val ch = key[index]) {
                '7' -> value = atob(value)
                '3' -> value = value.reversed()
                else -> {
                    val shift = (26 - ((ch.code - 96) % 26)) % 26
                    value = shiftLetters(value, shift)
                }
            }
        }

        if (key.length > 2048) value = value.reversed()
        if (value.length < 2) return ""

        val size = value.length
        val swaps = IntArray(size)
        for (index in size - 1 downTo 1) {
            permutationSeed = (permutationSeed * 97 + 41) % 65519
            swaps[index] = permutationSeed % (index + 1)
        }

        val chars = value.toCharArray()
        for (index in 1 until size) {
            val swapWith = swaps[index]
            val tmp = chars[index]
            chars[index] = chars[swapWith]
            chars[swapWith] = tmp
        }

        var state = xorSeed
        val output = StringBuilder(size)
        for (ch in chars) {
            val code = ch.code
            state = (state * 5 + xorStep) % 256
            output.append((code xor state).toChar())
            state = (state + code) % 256
        }
        return output.toString()
    }

    private fun shiftLetters(input: String, shift: Int): String {
        if (shift == 0) return input
        return buildString(input.length) {
            for (ch in input) {
                val code = ch.code
                when {
                    code in 65..90 -> append((((code - 65 + shift) % 26) + 65).toChar())
                    code in 97..122 -> append((((code - 97 + shift) % 26) + 97).toChar())
                    else -> append(ch)
                }
            }
        }
    }

    /** JS atob() karsiligi: baytlari Latin-1 karakterleri olarak dondurur. */
    private fun atob(input: String): String {
        val bytes = Base64.decode(input, Base64.DEFAULT)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
