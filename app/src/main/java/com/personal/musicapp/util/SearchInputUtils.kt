package com.personal.musicapp.util

import java.util.regex.Pattern

object SearchInputUtils {
    private val URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9./_?&=%#-]+")

    /**
     * Extracts the first valid HTTP(S) URL from free text (e.g., share snippets).
     */
    fun extractUrl(text: String): String? {
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    /**
     * Determines whether the given text is a share link, share snippet, or direct URL
     * rather than an ordinary search keyword.
     */
    fun isShareContentOrUrl(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.contains("douyin.com") ||
                lower.contains("iesdouyin.com") ||
                lower.contains("youtu.be") ||
                lower.contains("youtube.com") ||
                lower.contains("b23.tv") ||
                lower.contains("bilibili.com") ||
                lower.contains("music.163.com") ||
                lower.contains("kuwo.cn") ||
                URL_PATTERN.matcher(trimmed).find()
    }
}
