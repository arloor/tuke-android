package com.arloor.tuke.di

import android.content.Context
import com.arloor.tuke.BuildConfig
import com.arloor.tuke.core.agent.AgentRepository
import com.arloor.tuke.core.agent.AgentStreamKeepAlive
import com.arloor.tuke.core.network.EngineAuthInterceptor
import com.arloor.tuke.core.settings.SettingsStore
import com.arloor.tuke.core.update.AppReleaseRepository
import com.arloor.tuke.core.update.AppUpdateChecker
import com.arloor.tuke.engine.EngineController
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class AppContainer(context: Context) {
    val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val settingsStore = SettingsStore(appContext)
    val engineController = EngineController(appContext, settingsStore)

    private val updateHttpClient = OkHttpClient.Builder().build()
    val appUpdateChecker = AppUpdateChecker(
        context = appContext,
        releaseRepository = AppReleaseRepository(
            httpClient = updateHttpClient,
            json = json,
            latestReleaseApiUrl = BuildConfig.UPDATE_RELEASE_API_URL,
        ),
    )

    private val agentHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(EngineAuthInterceptor(engineController))
        .apply {
            if (BuildConfig.LOG_HTTP) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    val agentRepository = AgentRepository(
        httpClient = agentHttpClient,
        json = json,
        endpoint = { engineController.awaitEndpoint() },
    )

    val agentStreamKeepAlive = AgentStreamKeepAlive(appContext)
}
