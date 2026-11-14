package com.example.appblocker.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.example.appblocker.admin.UninstallProtectionManager
import com.example.appblocker.data.db.BlockedAppDao
import com.example.appblocker.data.db.BlockedDomainDao
import com.example.appblocker.data.model.BlockedApp
import com.example.appblocker.data.model.BlockedDomain
import kotlinx.coroutines.flow.Flow

class BlockRepository(
    private val context: Context,
    private val appDao: BlockedAppDao,
    private val domainDao: BlockedDomainDao,
    private val settings: SettingsRepository
) {

    val apps: Flow<List<BlockedApp>> = appDao.observeAll()
    val domains: Flow<List<BlockedDomain>> = domainDao.observeAll()

    suspend fun blockApp(
        packageName: String,
        label: String,
        until: Long,
        allowanceMs: Long = 0L,
        sinceMs: Long = 0L
    ) {
        appDao.upsert(BlockedApp(packageName, label, until, allowanceMs, sinceMs))
        markLocked(until)
    }

    suspend fun blockDomain(domain: String, until: Long) {
        domainDao.upsert(BlockedDomain(domain, until))
        markLocked(until)
    }

    /** Daily limit for a website: [allowanceMs] of use per day, then blocked until [until] (midnight). */
    suspend fun blockDomainAllowance(domain: String, until: Long, allowanceMs: Long) {
        domainDao.upsert(BlockedDomain(domain, until, allowanceMs))
        markLocked(until)
    }

    suspend fun addDomainUsage(domain: String, deltaMs: Long, now: Long) {
        if (deltaMs <= 0L) return
        domainDao.addUsage(domain, BlockedDomain.startOfDay(now), deltaMs)
    }

    suspend fun removeApp(packageName: String) {
        appDao.delete(packageName)
        refreshLockState(System.currentTimeMillis())
    }

    suspend fun removeDomain(domain: String) {
        domainDao.delete(domain)
        refreshLockState(System.currentTimeMillis())
    }

    suspend fun purgeExpired(now: Long) {
        appDao.deleteExpired(now)
        domainDao.deleteExpired(now)
        refreshLockState(now)
    }

    suspend fun hasActiveBlocks(now: Long): Boolean =
        appDao.countActive(now) + domainDao.countActive(now) > 0

    suspend fun hasActiveDomains(now: Long): Boolean = domainDao.countActive(now) > 0

    suspend fun nextExpiry(now: Long): Long? {
        val a = appDao.nextExpiry(now)
        val d = domainDao.nextExpiry(now)
        return listOfNotNull(a, d).minOrNull()
    }

    /**
     * Drops lock entries whose app is no longer installed. This does not depend on the
     * PACKAGE_FULLY_REMOVED broadcast, which can be missed while the process is not running.
     */
    suspend fun pruneUninstalled(): Int {
        val pm = context.packageManager
        var removed = 0
        for (app in appDao.getAll()) {
            if (!isInstalled(pm, app.packageName)) {
                appDao.delete(app.packageName)
                removed++
            }
        }
        if (removed > 0) refreshLockState(System.currentTimeMillis())
        return removed
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun markLocked(until: Long) {
        if (until > settings.lockedUntil) settings.lockedUntil = until
        UninstallProtectionManager.update(context, locking = true)
    }

    private suspend fun refreshLockState(now: Long) {
        if (hasActiveBlocks(now)) return
        settings.lockedUntil = 0L
        UninstallProtectionManager.update(context, locking = false)
    }

}
