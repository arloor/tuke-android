This directory contains the app-specific Agent harness. It uses only Go's standard library and exposes the small loopback API consumed by the Android client. The Go module version is 1.26.6.

`scripts/build-engine.ps1` cross-compiles it to the ignored `app/src/main/jniLibs/*/libtuke.so` files. The harness supports DeepSeek Responses streaming, local sessions, images/files, hosted web search status, `current_time`, bounded public-page `web_fetch`, cancellation, reconnect snapshots, and ADK v2.3.0-style tail-retention context compaction; it deliberately contains no general file tools or unrelated Tuke features.

Context compaction is local, not ADK: when the estimated prompt reaches 80% of the 500,000-token DeepSeek input budget, older events are summarized with thinking off and the last 10 session events stay raw. The summary is a prompt overlay; chat events and session activity time are unchanged.
