package com.arloor.tuke.feature.agent

import com.arloor.tuke.core.agent.AgentEvent
import com.arloor.tuke.core.agent.AgentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationStoreTest {
    @Test
    fun movingDraftToSessionKeepsItsStreamStateIsolated() {
        val store = AgentConversationStore()
        val draftKey = "draft:1"
        val sessionAKey = "session:a"
        val sessionBKey = "session:b"
        val eventA = textEvent("a", "A partial", partial = true)
        val eventB = textEvent("b", "B history", partial = false)

        val queued = QueuedAgentMessage(
            localId = "queue-1",
            text = "queued question",
            images = emptyList(),
            files = emptyList(),
            model = "deepseek",
        )
        store.put(
            draftKey,
            ConversationView(
                events = listOf(eventA),
                running = true,
                queuedMessages = listOf(queued),
            ),
        )
        store.put(sessionBKey, ConversationView(events = listOf(eventB)))

        store.move(draftKey, sessionAKey)
        store.update(sessionAKey) {
            it.copy(events = it.events + textEvent("a-final", "A final", partial = false))
        }

        assertTrue(store.get(sessionAKey).running)
        assertEquals(listOf("queued question"), store.get(sessionAKey).queuedMessages.map { it.text })
        assertEquals(listOf("A partial", "A final"), store.get(sessionAKey).events.map { it.text })
        assertFalse(store.get(sessionBKey).running)
        assertEquals(listOf("B history"), store.get(sessionBKey).events.map { it.text })
        assertTrue(store.get(draftKey).events.isEmpty())
    }

    private fun textEvent(id: String, text: String, partial: Boolean): AgentEvent {
        return AgentEvent(
            id = id,
            author = "assistant",
            partial = partial,
            parts = listOf(AgentPart(type = "text", text = text)),
        )
    }
}
