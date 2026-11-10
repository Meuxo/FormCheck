package com.meuxo.formcheck.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.meuxo.formcheck.data.local.FormCheckDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a real v1 database from the committed schema `1.json`, inserts v1 rows, then opens
 * it with Room v2 + MIGRATION_1_2. Room validates the migrated schema against the v2
 * entities on open and throws if any column, index or foreign key differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    private fun v1CreateSql(): String {
        val json = javaClass.classLoader!!.getResource("com.meuxo.formcheck.data.local.FormCheckDatabase/1.json")!!.readText()
        val entity = JSONObject(json).getJSONObject("database").getJSONArray("entities").getJSONObject(0)
        return entity.getString("createSql").replace("\${TABLE_NAME}", "sessions")
    }

    private fun createV1() {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v1CreateSql())
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '44b0f904909eacbf8d00f180ce2638f2')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO sessions (timestamp, mediaUri, overallScore, elbowFlareScore, elbowAngleAtRelease, kneeBendDepth, balanceScore, guideHandScore, phaseFeedbackJson) " +
            "VALUES (1700000000000, 'content://media/picker/0/video/12', 81, 85, 162.5, 131.0, 100, 85, '[{\"phase\":\"SETUP\",\"score\":100,\"notes\":[\"ok\"]}]')")
        db.execSQL("INSERT INTO sessions (timestamp, mediaUri, overallScore, elbowFlareScore, elbowAngleAtRelease, kneeBendDepth, balanceScore, guideHandScore, phaseFeedbackJson) " +
            "VALUES (1700000100000, 'content://media/external/video/media/99', 64, 40, 140.0, 165.0, 70, 50, '[]')")
        helper.close()
    }

    @Test fun `v1 rows survive as legacy sessions and v2 schema validates`() = runBlocking {
        createV1()
        val db = Room.databaseBuilder(context, FormCheckDatabase::class.java, name)
            .addMigrations(*FormCheckDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val dao = db.sessionDao()
        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        val old = rows.first { it.timestamp == 1700000000000 }
        assertEquals("FORM", old.type)
        assertEquals("LEGACY", old.status)
        assertEquals("content://media/picker/0/video/12", old.legacyMediaUri)
        // No v1 number is carried over as a v2 score.
        assertNull(old.overallScore)
        assertNull(old.mediaPath)
        val legacy = JSONObject(old.legacyJson!!)
        assertEquals(81, legacy.getInt("overallScore"))
        assertEquals("SETUP", legacy.getJSONArray("phaseFeedback").getJSONObject(0).getString("phase"))

        // New tables work and cascade on delete.
        dao.insertMetrics(listOf(com.meuxo.formcheck.data.local.MetricEntity(sessionId = old.id, timestamp = 1, shotIndex = null,
            metricId = "KNEE_BEND", value = 120.0, unit = "DEGREES", confidence = 0.9f, score = 100,
            frameStart = 1, frameEnd = 2, keyFrame = 1, reason = null, note = null)))
        assertEquals(1, dao.observeAllMeasuredMetrics().first().size)
        dao.deleteSession(old.id)
        assertTrue(dao.observeAllMeasuredMetrics().first().isEmpty())
        db.close()
    }
}
