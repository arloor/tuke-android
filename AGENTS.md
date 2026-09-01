# tuke-android

独立 AI 助手客户端。精简 Agent harness 位于 `engine/`；主进程中的 `TukeEngineService` 通过 `ProcessBuilder` 将 `libtuke.so` 作为独立 Go 子进程启动，并在 `127.0.0.1` 提供本地 HTTP。不要重新引入完整 tuke、通用工具、飞书、定时任务或遥测能力。

修改附件协议时同步检查 `engine/provider.go` 的 Responses 输入和 Android `AgentRepository` 的 `images/files[].data`。

## 上下文压缩

本 harness 不引入 `google.golang.org/adk`。行为对齐兄弟项目 tuke 在 ADK Go v2.3.0 上的 native tail retention：`TokenThreshold = 500000 * 0.80`，`EventRetentionSize = 10`，不开 sliding window。Session Event 保持不可变；摘要只作为模型输入 overlay，不投影到会话详情 / SSE，也不推进 `updatedAt`。压缩失败是 bookkeeping，本轮继续发送未压缩历史。
