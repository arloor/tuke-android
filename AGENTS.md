# tuke-android

独立 AI 助手客户端。精简 Agent harness 位于 `engine/`；主进程中的 `TukeEngineService` 通过 `ProcessBuilder` 将 `libtuke.so` 作为独立 Go 子进程启动，并在 `127.0.0.1` 提供本地 HTTP。不要重新引入完整 tuke、通用工具、飞书、定时任务或遥测能力。

修改附件协议时同步检查 `engine/provider.go` 的 Responses 输入和 Android `AgentRepository` 的 `images/files[].data`。
