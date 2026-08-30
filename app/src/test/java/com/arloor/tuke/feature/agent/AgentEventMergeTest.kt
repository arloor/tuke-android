package com.arloor.tuke.feature.agent

import com.arloor.tuke.core.agent.AgentEvent
import com.arloor.tuke.core.agent.AgentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mergeIncomingEvent 行为对照 Web 端 agent.tsx 的 mergeIncomingEvent。
 */
class AgentEventMergeTest {

    @Test
    fun `partial deltas with the same event id are merged`() {
        val first = assistantEvent(
            id = "response-1",
            responseId = "response-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "流")),
        )
        val second = assistantEvent(
            id = "response-1",
            responseId = "response-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "式")),
        )

        val merged = mergeIncomingEvent(listOf(first), second).single()

        assertTrue(merged.partial)
        assertEquals("流式", merged.text)
    }

    private fun assistantEvent(
        id: String,
        responseId: String? = null,
        partial: Boolean = false,
        reset: Boolean = false,
        parts: List<AgentPart>,
    ) = AgentEvent(
        id = id,
        responseId = responseId,
        invocationId = "inv-1",
        author = "assistant",
        partial = partial,
        reset = reset,
        parts = parts,
    )

    @Test
    fun `partial deltas with same response id accumulate text`() {
        val first = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "正在")),
        )
        val second = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "思考")),
        )

        val merged = listOf(first, second).reduce { events, incoming ->
            mergeIncomingEvent(listOf(events), incoming).single()
        }

        assertEquals("正在思考", merged.text)
        assertTrue(merged.partial)
    }

    @Test
    fun `completed snapshot replaces streamed partial keeping stable id`() {
        val partial = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "草稿")),
        )
        val snapshot = assistantEvent(
            id = "e-9",
            responseId = "r-1",
            partial = false,
            parts = listOf(AgentPart(type = "text", text = "正式回答")),
        )

        val events = mergeIncomingEvent(listOf(partial), snapshot)

        assertEquals(1, events.size)
        assertEquals("正式回答", events[0].text)
        assertFalse(events[0].partial)
        assertEquals("e-1", events[0].id)
    }

    @Test
    fun `response reset discards failed preview before retry deltas`() {
        val failed = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "失败草稿")),
        )
        val reset = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            reset = true,
            parts = emptyList(),
        )
        val retry = assistantEvent(
            id = "e-3",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "重新生成")),
        )

        val afterReset = mergeIncomingEvent(listOf(failed), reset)
        assertTrue(afterReset.isEmpty())
        val events = mergeIncomingEvent(afterReset, retry)
        assertEquals(listOf("重新生成"), events.map { it.text })
    }

    @Test
    fun `stale partial after completion is ignored`() {
        val snapshot = assistantEvent(
            id = "e-9",
            responseId = "r-1",
            partial = false,
            parts = listOf(AgentPart(type = "text", text = "正式回答")),
        )
        val stalePartial = assistantEvent(
            id = "e-10",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "迟到片段")),
        )

        val events = mergeIncomingEvent(listOf(snapshot), stalePartial)

        assertEquals(listOf("正式回答"), events.map { it.text })
    }

    @Test
    fun `duplicate event id does not double append`() {
        val event = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "内容")),
        )

        val events = mergeIncomingEvent(listOf(event), event)

        assertEquals(1, events.size)
        assertEquals("内容", events[0].text)
    }

    @Test
    fun `thinking and text parts merge independently`() {
        val first = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "thinking", text = "先想")),
        )
        val second = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "再想"),
                AgentPart(type = "text", text = "结论"),
            ),
        )

        val events = mergeIncomingEvent(listOf(first), second)

        assertEquals(1, events.size)
        assertEquals("先想再想", events[0].parts.first { it.isThinking }.text)
        assertEquals("结论", events[0].parts.first { it.isText }.text)
    }

    @Test
    fun `server echo replaces the optimistic local user message`() {
        val optimistic = AgentEvent(
            id = "local-1",
            author = "user",
            parts = listOf(AgentPart(type = "text", text = "你好")),
            local = true,
        )
        val echo = AgentEvent(
            id = "e-1",
            author = "user",
            parts = listOf(AgentPart(type = "text", text = "你好")),
        )

        val events = mergeIncomingEvent(listOf(optimistic), echo)

        assertEquals(1, events.size)
        assertEquals("e-1", events[0].id)
        assertFalse(events[0].local)
    }

    @Test
    fun `image echo only replaces optimistic message with the same image metadata`() {
        val first = AgentEvent(
            id = "local-1",
            author = "user",
            parts = listOf(AgentPart(type = "image", name = "one.png", mimeType = "image/png")),
            local = true,
        )
        val second = AgentEvent(
            id = "local-2",
            author = "user",
            parts = listOf(AgentPart(type = "image", name = "two.png", mimeType = "image/png")),
            local = true,
        )
        val echo = AgentEvent(
            id = "e-1",
            author = "user",
            parts = listOf(AgentPart(type = "image", name = "one.png", mimeType = "image/png", data = "cG5n")),
        )

        val events = mergeIncomingEvent(listOf(first, second), echo)

        assertEquals(listOf("e-1", "local-2"), events.map { it.id })
        assertFalse(events.first().local)
    }

    @Test
    fun `distinct responses append in order`() {
        val first = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = false,
            parts = listOf(AgentPart(type = "text", text = "第一")),
        )
        val second = assistantEvent(
            id = "e-2",
            responseId = "r-2",
            partial = false,
            parts = listOf(AgentPart(type = "text", text = "第二")),
        )

        val events = mergeIncomingEvent(listOf(first), second)

        assertEquals(listOf("第一", "第二"), events.map { it.text })
    }

    @Test
    fun `tool call parts merge by call id`() {
        val call = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "tool_call", name = "web_search", callId = "c-1")),
        )
        val result = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "tool_result", name = "web_search", callId = "c-1")),
        )
        val duplicateCall = assistantEvent(
            id = "e-3",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "tool_call", name = "web_search", callId = "c-1")),
        )

        val events = listOf(call, result, duplicateCall).fold(emptyList<AgentEvent>()) { acc, incoming ->
            mergeIncomingEvent(acc, incoming)
        }

        assertEquals(1, events.size)
        assertEquals(2, events[0].parts.size)
        assertTrue(events[0].parts.any { it.isToolResult })
    }

    @Test
    fun `hosted web search stays anchored below thinking without hiding text`() {
        val inProgress = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "第一次思考"),
                AgentPart(
                    type = "hosted_tool_status",
                    name = "web_search",
                    callId = "ws-1",
                    status = "in_progress",
                ),
            ),
        )
        val searching = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "，第二次思考"),
                AgentPart(
                    type = "hosted_tool_status",
                    name = "web_search",
                    callId = "ws-2",
                    status = "searching",
                ),
            ),
        )

        val separator = assistantEvent(
            id = "e-3",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "正文")),
        )
        val nextActivity = assistantEvent(
            id = "e-4",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "第三次思考"),
                AgentPart(
                    type = "hosted_tool_status",
                    name = "web_search",
                    callId = "ws-3",
                    status = "in_progress",
                ),
            ),
        )

        val collapsed = mergeIncomingEvent(listOf(inProgress), searching)
        val events = listOf(separator, nextActivity).fold(collapsed) { current, incoming ->
            mergeIncomingEvent(current, incoming)
        }

        assertEquals(1, events.size)
        assertEquals(3, events.single().parts.size)
        assertEquals("第一次思考，第二次思考第三次思考", events.single().parts[0].text)
        assertTrue(events.single().parts[1].isHostedToolStatus)
        assertEquals("ws-3", events.single().parts[1].callId)
        assertEquals("in_progress", events.single().parts[1].status)
        assertEquals("正文", events.single().parts[2].text)
    }

    @Test
    fun `late hosted web search status moves below nearest thinking`() {
        val preview = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "思考过程"),
                AgentPart(type = "text", text = "可见过程正文"),
            ),
        )
        val status = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(
                    type = "hosted_tool_status",
                    name = "web_search",
                    callId = "ws-1",
                    status = "searching",
                ),
            ),
        )

        val event = mergeIncomingEvent(listOf(preview), status).single()

        assertEquals(listOf("thinking", "hosted_tool_status", "text"), event.parts.map { it.type })
        assertEquals("思考过程", event.parts[0].text)
        assertEquals("可见过程正文", event.parts[2].text)
    }

    @Test
    fun `different hosted tool kinds keep distinct status rows`() {
        val initial = assistantEvent(
            id = "e-1",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(type = "thinking", text = "准备工具"),
                AgentPart(
                    type = "hosted_tool_status",
                    name = "file_search",
                    callId = "fs-1",
                    status = "in_progress",
                ),
            ),
        )
        val imageStatus = assistantEvent(
            id = "e-2",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(
                    type = "hosted_tool_status",
                    name = "image_generation",
                    callId = "ig-1",
                    status = "generating",
                ),
            ),
        )
        val completedFileSearch = assistantEvent(
            id = "e-3",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(
                    type = "hosted_tool_status",
                    name = "file_search",
                    callId = "fs-1",
                    status = "completed",
                ),
            ),
        )

        val event = listOf(imageStatus, completedFileSearch).fold(listOf(initial)) { events, incoming ->
            mergeIncomingEvent(events, incoming)
        }.single()

        assertEquals(
            listOf("thinking", "hosted_tool_status", "hosted_tool_status"),
            event.parts.map { it.type },
        )
        assertEquals(listOf("file_search", "image_generation"), event.parts.drop(1).map { it.name })
        assertEquals("completed", event.parts[1].status)
    }

    @Test
    fun `completed response aggregates one model round by type`() {
        val preview = assistantEvent(
            id = "preview",
            responseId = "r-1",
            partial = true,
            parts = listOf(
                AgentPart(
                    type = "hosted_tool_status",
                    name = "web_search",
                    callId = "search-1",
                    status = "searching",
                ),
            ),
        )
        val completed = assistantEvent(
            id = "completed",
            responseId = "r-1",
            parts = listOf(
                AgentPart(type = "text", text = "第一段正文。"),
                AgentPart(type = "thinking", text = "第一段思考。"),
                AgentPart(type = "tool_call", name = "search", callId = "call-1"),
                AgentPart(type = "thinking", text = "第二段思考。"),
                AgentPart(type = "text", text = "第二段正文。"),
            ),
        )

        val event = mergeIncomingEvent(listOf(preview), completed).single()

        assertEquals(listOf("thinking", "hosted_tool_status", "tool_call", "text"), event.parts.map { it.type })
        assertEquals("第一段思考。第二段思考。", event.parts[0].text)
        assertEquals("completed", event.parts[1].status)
        assertEquals("call-1", event.parts[2].callId)
        assertEquals("第一段正文。第二段正文。", event.parts[3].text)
    }

    @Test
    fun `session replay aggregates canonical body parts from one model round`() {
        val persisted = assistantEvent(
            id = "persisted",
            responseId = "r-1",
            parts = listOf(
                AgentPart(type = "text", text = "第一段正文。"),
                AgentPart(type = "text", text = "第二段正文。"),
            ),
        )

        val event = normalizePersistedEvents(listOf(persisted)).single()

        assertEquals(listOf("text"), event.parts.map { it.type })
        assertEquals("第一段正文。第二段正文。", event.text)
    }

    @Test
    fun `agent loop aggregates body independently by response id`() {
        val firstRound = assistantEvent(
            id = "round-1",
            responseId = "r-1",
            parts = listOf(
                AgentPart(type = "text", text = "第一轮上半段。"),
                AgentPart(type = "tool_call", name = "web_fetch", callId = "call-1"),
                AgentPart(type = "text", text = "第一轮下半段。"),
            ),
        )
        val secondRound = assistantEvent(
            id = "round-2",
            responseId = "r-2",
            parts = listOf(
                AgentPart(type = "text", text = "第二轮上半段。"),
                AgentPart(type = "text", text = "第二轮下半段。"),
            ),
        )

        val events = listOf(firstRound, secondRound).fold(emptyList<AgentEvent>()) { current, incoming ->
            mergeIncomingEvent(current, incoming)
        }

        assertEquals(2, events.size)
        assertEquals(listOf("r-1", "r-2"), events.map { it.responseId })
        assertEquals(listOf("第一轮上半段。第一轮下半段。", "第二轮上半段。第二轮下半段。"), events.map { it.text })
        assertEquals(listOf(1, 1), events.map { event -> event.parts.count { it.isText } })
    }

    @Test
    fun `session replay aggregates canonical text split by tool calls`() {
        val persisted = assistantEvent(
            id = "persisted",
            responseId = "r-1",
            parts = listOf(
                AgentPart(type = "text", text = "知乎抓不到。我改去读开源中国、少数派和能打开的"),
                AgentPart(type = "tool_call", name = "web_fetch", callId = "call-1"),
                AgentPart(type = "tool_call", name = "web_fetch", callId = "call-2"),
                AgentPart(type = "text", text = "评测页。"),
            ),
        )

        val event = normalizePersistedEvents(listOf(persisted)).single()

        assertEquals(listOf("tool_call", "tool_call", "text"), event.parts.map { it.type })
        assertEquals("知乎抓不到。我改去读开源中国、少数派和能打开的评测页。", event.text)
        assertFalse(event.partial)
    }

    @Test
    fun `tool turn keeps streaming partial text before canonical aggregation`() {
        val partial = assistantEvent(
            id = "partial",
            responseId = "r-1",
            partial = true,
            parts = listOf(AgentPart(type = "text", text = "我先核对一下价格数字，避免把预览价和今天的现")),
        )
        val streamed = mergeIncomingEvent(emptyList(), partial).single()

        assertTrue(streamed.partial)
        assertEquals("我先核对一下价格数字，避免把预览价和今天的现", streamed.text)

        val canonical = assistantEvent(
            id = "canonical",
            responseId = "r-1",
            parts = listOf(
                AgentPart(type = "text", text = "我先核对一下价格数字，避免把预览价和今天的现"),
                AgentPart(type = "tool_call", name = "web_fetch", callId = "call-1"),
                AgentPart(type = "text", text = "价混在一起。"),
            ),
        )
        val completed = mergeIncomingEvent(listOf(streamed), canonical).single()

        assertFalse(completed.partial)
        assertEquals(listOf("tool_call", "text"), completed.parts.map { it.type })
        assertEquals("我先核对一下价格数字，避免把预览价和今天的现价混在一起。", completed.text)
    }

    @Test
    fun `tool result is grouped into its invocation call`() {
        val events = listOf(
            assistantEvent(
                id = "call",
                parts = listOf(
                    AgentPart(type = "tool_call", name = "web_search", callId = "c-1"),
                ),
            ),
            assistantEvent(
                id = "result",
                parts = listOf(
                    AgentPart(type = "tool_result", name = "web_search", callId = "c-1"),
                ),
            ),
        )

        val index = buildToolExecutionIndex(events)

        assertTrue(index.byCallLocation.getValue(ToolPartLocation(0, 0)).result?.isToolResult == true)
        assertTrue(ToolPartLocation(1, 0) in index.matchedResultLocations)
        assertTrue(index.unmatchedResults.isEmpty())
    }

    @Test
    fun `same tool call id from another invocation is not grouped`() {
        val call = assistantEvent(
            id = "call",
            parts = listOf(AgentPart(type = "tool_call", callId = "shared")),
        )
        val result = assistantEvent(
            id = "result",
            parts = listOf(AgentPart(type = "tool_result", callId = "shared")),
        ).copy(invocationId = "inv-2")

        val index = buildToolExecutionIndex(listOf(call, result))

        assertEquals(null, index.byCallLocation.getValue(ToolPartLocation(0, 0)).result)
        assertTrue(ToolPartLocation(1, 0) in index.unmatchedResults)
        assertTrue(index.matchedResultLocations.isEmpty())
    }

    @Test
    fun `relative session time formatting`() {
        val now = 1_780_000_000_000L
        val justNow = java.time.Instant.ofEpochMilli(now - 30_000).toString()
        assertEquals("刚刚", formatSessionTime(justNow, now))
        val minutesAgo = java.time.Instant.ofEpochMilli(now - 5 * 60_000).toString()
        assertEquals("5 分钟前", formatSessionTime(minutesAgo, now))
        assertEquals("", formatSessionTime(null, now))
        assertEquals("", formatSessionTime("not-a-time", now))
    }

    @Test
    fun `completed conversation anchors at its last user question`() {
        val events = listOf(
            AgentEvent(id = "u-1", author = "user"),
            AgentEvent(id = "a-1", author = "assistant"),
            AgentEvent(id = "u-2", author = "human"),
            AgentEvent(id = "a-2", author = "assistant"),
        )

        assertEquals(2, lastUserEventIndex(events))
        assertEquals(null, lastUserEventIndex(events.filterNot { it.isUser }))
    }
}
