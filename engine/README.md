This directory contains the app-specific Agent harness. It uses only Go's standard library and exposes the small loopback API consumed by the Android client.

`scripts/build-engine.ps1` cross-compiles it to the ignored `app/src/main/jniLibs/*/libtuke.so` files. The harness supports DeepSeek Responses streaming, local sessions, images/files, hosted web search status, cancellation, and reconnect snapshots; it deliberately contains no general tool runtime or unrelated Tuke features.
