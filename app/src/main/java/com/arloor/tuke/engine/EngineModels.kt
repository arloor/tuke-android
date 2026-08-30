package com.arloor.tuke.engine

data class EngineState(
    val hasApiKey: Boolean = false,
    val starting: Boolean = false,
    val ready: Boolean = false,
    val port: Int? = null,
    val error: String? = null,
) {
    val baseUrl: String?
        get() = port?.let { "http://127.0.0.1:$it" }
}

data class EngineEndpoint(
    val baseUrl: String,
    val token: String,
)
