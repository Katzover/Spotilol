package com.project.lol.util

import com.project.lol.BuildConfig

object BuildInfo {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ID_LENGTH = 10

    val id: String by lazy { fingerprint() }

    private fun fingerprint(): String {
        val seed = buildString {
            append(BuildConfig.APPLICATION_ID)
            append(':')
            append(BuildConfig.VERSION_NAME)
            append(':')
            append(BuildConfig.VERSION_CODE)
            append(':')
            append(BuildConfig.BUILD_TYPE)
        }
        var hash = 0xcbf29ce484222325uL
        for (char in seed) {
            hash = (hash xor char.code.toULong()) * 0x100000001b3uL
        }
        val builder = StringBuilder(ID_LENGTH)
        var value = hash
        while (builder.length < ID_LENGTH) {
            builder.append(ALPHABET[(value % 36uL).toInt()])
            value /= 36uL
            if (value == 0uL) {
                value = hash xor 0x9e3779b97f4a7c15uL
            }
        }
        return builder.toString()
    }
}
