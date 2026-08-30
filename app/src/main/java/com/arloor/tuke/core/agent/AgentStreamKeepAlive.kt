package com.arloor.tuke.core.agent

import android.content.Context

class AgentStreamKeepAlive(
    private val context: Context,
) {
    fun acquire() {
        AgentStreamService.start(context)
    }

    fun release() {
        AgentStreamService.stop(context)
    }
}
