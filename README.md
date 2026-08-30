# Tuke Android

本地嵌入式 AI 助手。仓库内的精简 Agent harness 作为应用主进程管理的独立 Go 子进程运行；UI 通过 `127.0.0.1` 访问仅供本应用使用的 `/api/chat/*`。

## 和 stock-android-native 的差别

- 无登录、无托管后端、无 media-gateway
- 自行配置 DeepSeek API Key
- 图片以 Base64 随 `run_sse` 发送，保存在本机
- 切到其他应用时，生成中的会话靠前台服务保活
- harness 仅包含 DeepSeek Responses 流、会话、附件、`current_time` 和受限的 `web_fetch`；不包含通用文件工具、技能、定时任务、飞书、遥测或远程分享

## 开发

1. 安装 JDK 17、Android SDK、Go 1.26
2. 复制 `local.properties` 或确认 `sdk.dir`
3. 构建引擎：`powershell -File scripts/build-engine.ps1`
4. 安装：`.\gradlew.bat :app:installDebug`

首次打开应用后到「设置」填入 DeepSeek API Key。

## 应用更新与发布

应用启动、回到前台以及进程存活期间会自动检查
`arloor/tuke-android` 的 GitHub latest release；设置页也可以手动检查、下载 APK，
并在用户授权“安装未知应用”后继续拉起系统安装器。需要改用其他仓库时可通过 Gradle 属性
`UPDATE_RELEASE_API_URL` 覆盖 API 地址。

发布约定：

- tag 使用 `v<versionName>+code.<versionCode>`，例如 `v0.2.0+code.2`；
- release 上传名为 `release.apk` 的 APK（没有该名称时会回退到首个 `.apk` asset）；
- 每次发布必须递增 `versionCode`，并使用与已安装版本相同的 release 私钥签名；
- 将 `keystore.properties.example` 复制为 `keystore.properties` 后填写仓库外保存的私钥信息。
  未配置时 `assembleRelease` 只生成 unsigned APK，不会回退使用公开的 debug key。

`libtuke.so` 使用 `-trimpath -s -w` 去掉本地路径和调试符号，但这不是保密措施。发布 APK 后，
DeepSeek 接口路径、错误文本、移动端提示词及主要控制流仍可能被静态分析。仓库和二进制中不得硬编码
API Key、Token、签名私钥或其他凭据。harness 的私有会话回放数据只保存在应用沙箱内。

跳过引擎构建（只编 UI）：

```
$env:TUKE_ENGINE_PREBUILT=1
.\gradlew.bat :app:assembleDebug
```
