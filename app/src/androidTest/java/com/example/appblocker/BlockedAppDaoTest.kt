package com.example.appblocker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.appblocker.data.db.AppDatabase
import com.example.appblocker.data.db.BlockedAppDao
import com.example.appblocker.data.model.BlockedApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedAppDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BlockedAppDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.blockedAppDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun countActive_ignoresExpired() = runBlocking {
        dao.upsert(BlockedApp("com.expired", "Expired", 1_000L))
        dao.upsert(BlockedApp("com.active", "Active", 10_000L))
        assertEquals(1, dao.countActive(5_000L))
    }

    @Test
    fun deleteExpired_removesOnlyExpired() = runBlocking {
        dao.upsert(BlockedApp("com.expired", "Expired", 1_000L))
        dao.upsert(BlockedApp("com.active", "Active", 10_000L))
        dao.deleteExpired(5_000L)
        assertEquals(1, dao.countActive(5_000L))
        assertEquals(10_000L, dao.nextExpiry(5_000L))
    }

    @Test
    fun nextExpiry_nullWhenNothingActive() = runBlocking {
        dao.upsert(BlockedApp("com.expired", "Expired", 1_000L))
        assertNull(dao.nextExpiry(5_000L))
    }

}
