package com.arloor.tuke.core.agent

import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSseParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `named frames dispatch by event type`() {
        val source = Buffer().writeUtf8(
            """
            event: session
            data: {"sessionId":"s-1","title":"你好","isNew":true}

            event: event
            data: {"id":"e-1","author":"assistant","partial":true,"timestamp":"2026-08-08T00:00:00Z","parts":[{"type":"text","text":"你"}]}

            event: event
            data: {"id":"e-2","author":"assistant","partial":false,"timestamp":"2026-08-08T00:00:01Z","parts":[{"type":"text","text":"你好!"}]}

            event: done
            data: {"sessionId":"s-1","status":"completed"}

            """.trimIndent(),
        )
        val packets = mutableListOf<AgentStreamPacket>()

        readAgentSse(source, json, packets::add)

        assertEquals(4, packets.size)
        val session = packets[0] as AgentStreamPacket.Session
        assertEquals("s-1", session.sessionId)
        assertEquals("你好", session.title)
        assertTrue(session.isNew)
        val first = (packets[1] as AgentStreamPacket.Event).event
        assertEquals("e-1", first.id)
        assertTrue(first.partial)
        assertEquals("你", first.text)
        val second = (packets[2] as AgentStreamPacket.Event).event
        assertEquals("你好!", second.text)
        assertEquals("s-1", (packets[3] as AgentStreamPacket.Done).sessionId)
    }

    @Test
    fun `named error frame becomes failure packet`() {
        val source = Buffer().writeUtf8(
            """
            event: error
            data: {"code":"internal","message":"模型调用失败"}

            """.trimIndent(),
        )
        val packets = mutableListOf<AgentStreamPacket>()

        readAgentSse(source, json, packets::add)

        assertEquals(
            listOf(AgentStreamPacket.Failure("模型调用失败")),
            packets,
        )
    }

    @Test
    fun `plain done marker ends the stream`() {
        val source = Buffer().writeUtf8("data: [DONE]\n\n")
        val packets = mutableListOf<AgentStreamPacket>()

        readAgentSse(source, json, packets::add)

        assertEquals(listOf(AgentStreamPacket.Done(null)), packets)
    }

    @Test
    fun `untyped message frame with embedded type is dispatched`() {
        val source = Buffer().writeUtf8(
            """
            data: {"type":"session","data":{"sessionId":"s-9","title":"内嵌"}}

            """.trimIndent(),
        )
        val packets = mutableListOf<AgentStreamPacket>()

        readAgentSse(source, json, packets::add)

        assertEquals(
            listOf(AgentStreamPacket.Session("s-9", "内嵌", isNew = false)),
            packets,
        )
    }
}
