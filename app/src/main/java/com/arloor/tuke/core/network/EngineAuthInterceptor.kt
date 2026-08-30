package com.arloor.tuke.core.network

import com.arloor.tuke.engine.EngineController
import okhttp3.Interceptor
import okhttp3.Response

class EngineAuthInterceptor(
    private val engineController: EngineController,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val endpoint = engineController.endpoint()
        val builder = chain.request().newBuilder()
            .header("X-Tuke-User-ID", "local")
        if (endpoint != null) {
            builder.header("Authorization", "Bearer ${endpoint.token}")
        }
        return chain.proceed(builder.build())
    }
}
