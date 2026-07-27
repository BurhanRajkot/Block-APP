package com.blockapp.android.data

import android.content.Context
import com.blockapp.android.alarm.AlarmScheduler
import com.blockapp.android.util.ProtectedPackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Single read/write entry point for lock state, used by the UI, the accessibility service,
 * and the alarm/boot receivers.
 */
class BlockRepository(
    private val context: Context,
    private val dao: BlockDao,
    private val scope: CoroutineScope,
) {
    private val _activeLocks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * packageName -> blockUntil epoch millis, kept in memory so the accessibility service can
     * check it synchronously on every window-state-changed event instead of hitting Room.
     */
    val activeLocks: StateFlow<Map<String, Long>> = _activeLocks

    val activeEntities: Flow<List<BlockedAppEntity>> get() = dao.observeActive()

    init {
        dao.observeActive()
            .onEach { list -> _activeLocks.value = list.associate { it.packageName to it.blockUntil } }
            .launchIn(scope)
    }

    /**
     * Locking Settings, the package installer, or Play Store would kick those packages home
     * the instant they open — including for this app's own setup/removal flows, which live
     * inside Settings. That's a self-inflicted lockout, so it's refused here as a backstop
     * even though the app picker already excludes them from the list (see ProtectedPackages).
     *
     * **Locks only ever extend, never shorten.** [BlockDao.insert] is REPLACE, so without the
     * merge below, re-locking an already-locked app for a shorter time would overwrite its
     * blockUntil and end the running lock early — a fully in-app early exit, which is the one
     * thing this app exists to prevent. An unlock key is the only thing allowed to bring a
     * blockUntil forward (see [applyUnlockKey]), because that key can only be minted off-device.
     *
     * The original [BlockedAppEntity.blockedAt] is kept for the same reason it's stored at all:
     * it's the denominator of the progress ring on HomeScreen and the block screen, so resetting
     * it on an extend would snap a nearly-finished lock back to 0%.
     */
    fun lockApp(packageName: String, label: String, blockUntil: Long) {
        if (packageName in ProtectedPackages.ALL) return
        scope.launch {
            val existing = dao.getActiveLock(packageName)
            val effectiveUntil = maxOf(blockUntil, existing?.blockUntil ?: 0L)
            dao.insert(
                BlockedAppEntity(
                    packageName = packageName,
                    appLabel    = label,
                    blockedAt   = existing?.blockedAt ?: System.currentTimeMillis(),
                    blockUntil  = effectiveUntil,
                ),
            )
            AlarmScheduler.schedule(context, packageName, effectiveUntil)
        }
    }

    /**
     * Direct DB read (bypassing the in-memory [activeLocks] cache), used by the accessibility
     * service right after it (re)connects — e.g. after the process was killed and restarted —
     * when the in-memory cache may not have caught up with Room's async Flow yet.
     */
    suspend fun getActiveLockUntil(packageName: String): Long? = getActiveLock(packageName)?.blockUntil

    /**
     * The full record for one active lock, for UI that needs more than the expiry time —
     * BlockOverlayActivity uses [BlockedAppEntity.blockedAt] to show how far through the lock the
     * user already is. Deliberately not folded into [activeLocks]: that map is read on every
     * window-state event by the accessibility service, so it stays a bare packageName -> Long to
     * keep that path a single map lookup, and a display-only field must not widen it.
     */
    suspend fun getActiveLock(packageName: String): BlockedAppEntity? = dao.getActiveLock(packageName)

    suspend fun expireDuePackage(packageName: String) {
        val lock = dao.getActiveLock(packageName) ?: return
        if (lock.blockUntil <= System.currentTimeMillis()) {
            dao.deactivate(packageName)
        }
    }

    suspend fun expireAllDue() {
        val now = System.currentTimeMillis()
        dao.getActiveOnce().filter { it.blockUntil <= now }.forEach { dao.deactivate(it.packageName) }
    }

    suspend fun getActiveOnce(): List<BlockedAppEntity> = dao.getActiveOnce()

    /** Returns false only when the key's nonce was already used (replay). */
    suspend fun applyUnlockKey(targetPackage: String, newUntil: Long, nonce: String): Boolean {
        if (dao.isNonceUsed(nonce) > 0) return false
        dao.markNonceUsed(UsedNonceEntity(nonce, System.currentTimeMillis()))

        val now = System.currentTimeMillis()
        if (targetPackage == "*") {
            if (newUntil <= now) {
                dao.deactivateAll()
            } else {
                dao.getActiveOnce().forEach { entity ->
                    dao.updateBlockUntil(entity.packageName, newUntil)
                    AlarmScheduler.schedule(context, entity.packageName, newUntil)
                }
            }
        } else {
            if (newUntil <= now) {
                dao.deactivate(targetPackage)
            } else {
                dao.updateBlockUntil(targetPackage, newUntil)
                AlarmScheduler.schedule(context, targetPackage, newUntil)
            }
        }
        return true
    }
}
