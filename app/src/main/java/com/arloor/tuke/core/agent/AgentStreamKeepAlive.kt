package com.arloor.tuke.core.agent

import android.content.Context
import com.arloor.tuke.engine.EngineController

class AgentStreamKeepAlive(
    private val context: Context,
    private val engineController: EngineController,
) {
    fun acquire() {
        AgentStreamService.start(context)
        engineController.notifyRunBegin()
    }

    fun release() {
        engineController.notifyRunEnd()
        AgentStreamService.stop(context)
    }
}
