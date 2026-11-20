package com.example.sharkmentor.core.notification

object ActiveChatTracker {
    @Volatile
    var activeChannelId: String? = null

    @Volatile
    var isChatForeground: Boolean = false

    fun enterChat(channelId: String?) {
        activeChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() }
        isChatForeground = true
    }

    fun exitChat(channelId: String? = null) {
        if (channelId == null || activeChannelId?.equals(channelId.trim(), ignoreCase = true) == true) {
            activeChannelId = null
            isChatForeground = false
        }
    }

    fun isViewingChannel(channelId: String?): Boolean {
        if (!isChatForeground) return false
        val current = activeChannelId ?: return false
        if (channelId.isNullOrBlank()) return true
        return current.equals(channelId.trim(), ignoreCase = true)
    }
}
