package me.rerere.rikkahub.utils

fun String.isRemoteHttpUrl(): Boolean {
    val value = trim()
    return value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
}
