package com.meuxo.formcheck.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MetricEntity::class, ShotEntity::class],
    version = 2,
    exportSchema = true
)
abstract class FormCheckDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        /**
         * v1 → v2.
         *
         * v1 stored only a picker URI and six scores, some of which were placeholders (see
         * docs/AUDIT.md). v1 rows are kept as LEGACY sessions: the date and URI survive, the old
         * numbers are moved into `legacyJson` for transparency, and no v1 number is shown as a
         * v2 score. The `sessions` table is rebuilt because several columns change nullability.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `title` TEXT, `status` TEXT NOT NULL, `statusMessage` TEXT, " +
                        "`dirPath` TEXT, `mediaPath` TEXT, `trackPath` TEXT, `thumbPath` TEXT, `resultPath` TEXT, " +
                        "`overallScore` INTEGER, `includedMetrics` INTEGER, `totalMetrics` INTEGER, " +
                        "`makes` INTEGER, `attempts` INTEGER, `durationMs` INTEGER, `sizeBytes` INTEGER, " +
                        "`analyzedAt` INTEGER, `legacyMediaUri` TEXT, `legacyJson` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `sessions_new` (`id`, `timestamp`, `type`, `title`, `status`, `statusMessage`, " +
                        "`legacyMediaUri`, `legacyJson`) " +
                        "SELECT `id`, `timestamp`, 'FORM', NULL, 'LEGACY', " +
                        "'Analyzed with FormCheck v1. v1 scores were not reliable and are not shown.', `mediaUri`, " +
                        "'{\"overallScore\":' || `overallScore` || ',\"elbowFlareScore\":' || `elbowFlareScore` || " +
                        "',\"elbowAngleAtRelease\":' || `elbowAngleAtRelease` || ',\"kneeBendDepth\":' || `kneeBendDepth` || " +
                        "',\"balanceScore\":' || `balanceScore` || ',\"guideHandScore\":' || `guideHandScore` || " +
                        "',\"phaseFeedback\":' || `phaseFeedbackJson` || '}' FROM `sessions`"
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_type` ON `sessions` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_timestamp` ON `sessions` (`timestamp`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metrics` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `shotIndex` INTEGER, `metricId` TEXT NOT NULL, `value` REAL, " +
                        "`unit` TEXT NOT NULL, `confidence` REAL NOT NULL, `score` INTEGER, `frameStart` INTEGER, " +
                        "`frameEnd` INTEGER, `keyFrame` INTEGER, `reason` TEXT, `note` TEXT, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metrics_sessionId` ON `metrics` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metrics_metricId` ON `metrics` (`metricId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                        "`shotIndex` INTEGER NOT NULL, `startFrame` INTEGER NOT NULL, `releaseFrame` INTEGER, " +
                        "`endFrame` INTEGER NOT NULL, `outcome` TEXT, `outcomeCorrected` INTEGER NOT NULL, " +
                        "`courtX` REAL, `courtY` REAL, `zone` TEXT, `overallScore` INTEGER, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shots_sessionId` ON `shots` (`sessionId`)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
