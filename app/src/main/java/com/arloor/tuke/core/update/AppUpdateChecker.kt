package com.arloor.tuke.core.update

import android.content.Context
import com.arloor.tuke.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppUpdateCheckState(
    val checking: Boolean = false,
    val info: AppReleaseCheckResult? = null,
    val error: String? = null,
)

/** 启动时检查、回到前台时按最小间隔复查，并在进程存活期间定时轮询。 */
class AppUpdateChecker(
    context: Context,
    private val releaseRepository: AppReleaseRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppUpdateCheckState())

    val state: StateFlow<AppUpdateCheckState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                check(force = false)
                delay(PERIODIC_CHECK_INTERVAL_MS)
            }
        }
    }

    fun checkNow() {
        scope.launch { check(force = true) }
    }

    fun onAppForeground() {
        scope.launch { check(force = false) }
    }

    private suspend fun check(force: Boolean) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
            if (!force && lastCheckAt > 0L && now - lastCheckAt < AUTO_CHECK_MIN_INTERVAL_MS) {
                return
            }

            _state.update { it.copy(checking = true, error = if (force) null else it.error) }
            runCatching {
                releaseRepository.check(
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                )
            }.onSuccess { info ->
                prefs.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
                _state.update { it.copy(checking = false, info = info, error = null) }
            }.onFailure { error ->
                prefs.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
                _state.update {
                    it.copy(
                        checking = false,
                        info = if (force) null else it.info,
                        error = if (force) error.message ?: "检查更新失败" else it.error,
                    )
                }
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "app_update_checker"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val AUTO_CHECK_MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val PERIODIC_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L
    }
}
