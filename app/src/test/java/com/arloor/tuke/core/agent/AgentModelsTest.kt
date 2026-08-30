package com.arloor.tuke.core.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelsTest {

    @Test
    fun `base64 image data uri is decoded for preview`() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            decodeAgentImageDataUri("data:image/png;base64,AQID"),
        )
        assertNull(decodeAgentImageDataUri("https://example.com/image.png"))
        assertNull(decodeAgentImageDataUri("data:image/png,not-base64"))
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deepseek model supports images but not document files`() {
        assertTrue(agentModelSupportsImages(AGENT_MODEL_DEEPSEEK))
        assertFalse(agentModelSupportsFiles(AGENT_MODEL_DEEPSEEK))
        assertEquals("深度推理，支持图像理解", agentModelSubtitle(AGENT_MODEL_DEEPSEEK))
    }

    @Test
    fun `agent settings decode the single available model`() {
        val settings = json.decodeFromString<AgentSettings>(
            """{"defaultModel":"deepseek","models":["deepseek"]}""",
        )
        assertEquals(AGENT_MODEL_DEEPSEEK, settings.defaultModel)
        assertEquals(listOf(AGENT_MODEL_DEEPSEEK), settings.models)
    }

    @Test
    fun `session list decodes lightweight paginated items`() {
        val response = json.decodeFromString<SessionListResponse>(
            """
            {
              "sessions": [{
                "id": "session-1",
                "title": "第一段对话",
                "updatedAt": "2026-08-08T00:00:00Z"
              }],
              "nextCursor": "next-page",
              "hasMore": true
            }
            """.trimIndent(),
        )

        assertEquals(listOf("session-1"), response.sessions.map { it.id })
        assertEquals("第一段对话", response.sessions[0].title)
        assertEquals(false, response.sessions[0].starred)
        assertEquals("next-page", response.nextCursor)
        assertTrue(response.hasMore)
    }

    @Test
    fun `session detail decodes typed parts and usage`() {
        val detail = json.decodeFromString<SessionDetailResponse>(
            """
            {
              "session": {"id": "session-1", "title": "对话"},
              "running": true,
              "events": [
                {
                  "id": "e-1",
                  "author": "user",
                  "timestamp": "2026-08-08T00:00:00Z",
                  "parts": [{"type": "text", "text": "你好"}]
                },
                {
                  "id": "e-2",
                  "responseId": "inv|user:0",
                  "invocationId": "inv",
                  "author": "assistant",
                  "timestamp": "2026-08-08T00:00:01Z",
                  "parts": [
                    {"type": "thinking", "text": "先想想"},
                    {"type": "hosted_tool_status", "name": "web_search", "callId": "ws-1", "status": "searching"},
                    {"type": "tool_call", "name": "web_search", "callId": "c-1", "args": {"q": "x"}},
                    {"type": "text", "text": "你好!"}
                  ],
                  "usage": {"inputTokens": 10, "outputTokens": 5, "totalTokens": 15}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("session-1", detail.session?.id)
        assertTrue(detail.running)
        assertEquals(2, detail.events.size)
        val assistant = detail.events[1]
        assertTrue(assistant.parts[0].isThinking)
        assertTrue(assistant.parts[1].isHostedToolStatus)
        assertEquals("searching", assistant.parts[1].status)
        assertTrue(assistant.parts[2].isToolCall)
        assertEquals("web_search", assistant.parts[2].name)
        assertEquals("你好!", assistant.text)
        assertEquals(15, assistant.usage?.totalTokens)
    }

    @Test
    fun `event text joins only text parts`() {
        val event = AgentEvent(
            id = "e-1",
            parts = listOf(
                AgentPart(type = "thinking", text = "思考"),
                AgentPart(type = "text", text = "正式"),
                AgentPart(type = "text", text = "回复"),
            ),
        )
        assertEquals("正式回复", event.text)
        assertNull(event.responseId)
    }

    @Test
    fun `hosted tool status labels distinguish known and unknown tools`() {
        assertEquals(HostedToolKind.WebSearch, agentHostedToolKind("web_search_call"))
        assertEquals(HostedToolKind.ComputerUse, agentHostedToolKind("computer_use_preview"))
        assertEquals("正在搜索网页", agentHostedToolStatusLabel("web_search", "searching"))
        assertEquals("文件搜索完成", agentHostedToolStatusLabel("file_search", "completed"))
        assertEquals("正在执行代码", agentHostedToolStatusLabel("code_interpreter", "in_progress"))
        assertEquals(HostedToolKind.ImageSearch, agentHostedToolKind("search_images"))
        assertEquals("正在搜索图片", agentHostedToolStatusLabel("search_images", "in_progress"))
        assertEquals("图片理解完成", agentHostedToolStatusLabel("view_image", "completed"))
        assertEquals("正在理解 X 视频", agentHostedToolStatusLabel("view_x_video", "in_progress"))
        assertEquals("图片生成失败", agentHostedToolStatusLabel("image_generation", "failed"))
        assertEquals("正在使用托管工具 vector_lookup", agentHostedToolStatusLabel("vector_lookup", "running"))
        assertTrue(agentHostedToolStatusTerminal("cancelled"))
    }

    @Test
    fun `document mime type is inferred from supported extension`() {
        assertNull(agentDocumentMimeType("annual-report.PDF"))
        assertEquals("text/markdown", agentDocumentMimeType("notes.md"))
        assertEquals("text/plain", agentDocumentMimeType("main.kt"))
        assertNull(agentDocumentMimeType("archive.zip"))
    }

    @Test
    fun `image mime type uses declared type or supported extension`() {
        assertEquals("image/webp", agentImageMimeType("chart.bin", "image/webp"))
        assertEquals("image/jpeg", agentImageMimeType("photo.JPG", null))
        assertNull(agentImageMimeType("document.pdf", "application/pdf"))
    }

}
