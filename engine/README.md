This directory contains the app-specific Agent harness. It uses only Go's standard library and exposes the small loopback API consumed by the Android client.

`scripts/build-engine.ps1` cross-compiles it to the ignored `app/src/main/jniLibs/*/libtuke.so` files. The harness supports DeepSeek Responses streaming, local sessions, images/files, hosted web search status, `current_time`, bounded public-page `web_fetch`, cancellation, and reconnect snapshots; it deliberately contains no general file tools or unrelated Tuke features.
