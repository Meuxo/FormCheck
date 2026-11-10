package com.meuxo.formcheck.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meuxo.formcheck.R
import com.meuxo.formcheck.analysis.SessionAnalyzer
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.tracking.geom.Vec2
import com.meuxo.formcheck.tracking.pipeline.ClipTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full analysis of one saved session as a foreground job (spec 5), so leaving the
 * screen or the app does not lose the work. Progress is published through WorkManager.
 */
@HiltWorker
class AnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SessionRepository,
    private val files: SessionFiles,
    private val tracker: ClipTracker,
    private val analyzer: SessionAnalyzer,
    private val settings: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val id = inputData.getLong(KEY_SESSION, -1)
        if (id < 0) return@withContext Result.failure()
        try {
            runCatching { setForeground(foregroundInfo(0)) }
            repository.setStatus(id, SessionStatus.ANALYZING)
            val p = files.readParams(id) ?: error("Session settings missing")
            val user = settings.current()
            val media = files.media(id)
            require(media.exists()) { "Video file missing" }

            var lastPct = -1
            val info = com.meuxo.formcheck.tracking.pipeline.VideoFrameDecoder().probe(media)
            val thumbAtUs = info.durationUs / 2
            val reuse = inputData.getBoolean(KEY_REUSE_TRACK, false)
            val saved = if (reuse) files.readTrack(id) else null
            val clip = if (saved != null) withUserRim(saved, p) else tracker.track(
                media,
                ClipTracker.Options(
                    trackBall = p.type != SessionType.JUMP,
                    poseEveryNth = if (p.type == SessionType.SHOOTAROUND) 2 else 1,
                    shooterSeed = p.shooterSeed?.let { Vec2(it.x, it.y) },
                    rimTapsNorm = if (p.rimLeft != null && p.rimRight != null)
                        Vec2(p.rimLeft.x, p.rimLeft.y) to Vec2(p.rimRight.x, p.rimRight.y) else null
                ),
                onProgress = { f ->
                    val pct = (f * 95).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        setProgressAsync(workDataOf(KEY_PROGRESS to pct))
                        if (pct % 5 == 0) notify(pct)
                    }
                }
            )
            // Thumbnail: one frame at the midpoint, decoded separately so no bitmap is kept alive.
            if (saved == null) thumbnailAt(media, thumbAtUs)?.let { files.writeThumb(id, it); it.recycle() }

            val result = analyzer.analyze(clip, p, user)
            files.writeTrack(id, clip)
            repository.saveAnalysis(id, result, hasThumb = files.thumb(id).exists())
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                repository.setStatus(id, SessionStatus.PENDING, "Analysis was stopped. Open the session to retry.")
                throw t
            }
            repository.setStatus(id, SessionStatus.FAILED, t.message ?: t.javaClass.simpleName)
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Analysis failed")))
        }
    }

    /** Applies rim taps made after tracking (recalibration) to a saved clip. */
    private fun withUserRim(c: com.meuxo.formcheck.tracking.TrackedClip, p: com.meuxo.formcheck.domain.model.SessionParams): com.meuxo.formcheck.tracking.TrackedClip {
        val l = p.rimLeft ?: return c
        val r = p.rimRight ?: return c
        val rim = com.meuxo.formcheck.tracking.RimObs(((l.x + r.x) / 2 * c.width).toFloat(), ((l.y + r.y) / 2 * c.height).toFloat(),
            (kotlin.math.abs(r.x - l.x) * c.width).toFloat(), 1f, com.meuxo.formcheck.tracking.RimSource.USER_TAP)
        return com.meuxo.formcheck.tracking.TrackedClip(c.width, c.height, c.frames, rim, c.rimMovedAtFrame, c.timeScale)
    }

    private fun thumbnailAt(file: java.io.File, timeUs: Long): Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val b = mmr.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = 480.0 / maxOf(b.width, b.height)
            if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true).also { b.recycle() } else b
        } catch (_: Throwable) { null } finally { mmr.release() }
    }

    private fun notify(pct: Int) {
        runCatching { setForegroundAsync(foregroundInfo(pct)) }
    }

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Analysis", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_basketball_foreground)
            .setContentTitle("Analyzing your session")
            .setContentText("$pct% · runs on this phone, nothing is uploaded")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notifId = 1000 + (inputData.getLong(KEY_SESSION, 0) % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }

    companion object {
        const val KEY_SESSION = "sessionId"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        /** Re-run analysis on the saved track (after recalibration) instead of decoding again. */
        const val KEY_REUSE_TRACK = "reuseTrack"
        private const val CHANNEL = "analysis"
        fun uniqueName(id: Long) = "analyze-$id"
    }
}

data class AnalysisProgress(val state: WorkInfo.State?, val percent: Int, val error: String?)

@Singleton
class AnalysisScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun enqueue(sessionId: Long, reuseTrack: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_SESSION to sessionId, AnalysisWorker.KEY_REUSE_TRACK to reuseTrack))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        wm.enqueueUniqueWork(AnalysisWorker.uniqueName(sessionId), if (reuseTrack) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(sessionId: Long) { wm.cancelUniqueWork(AnalysisWorker.uniqueName(sessionId)) }

    fun observe(sessionId: Long): Flow<AnalysisProgress> =
        wm.getWorkInfosForUniqueWorkFlow(AnalysisWorker.uniqueName(sessionId)).map { list ->
            val w = list.lastOrNull()
            AnalysisProgress(
                w?.state,
                w?.progress?.getInt(AnalysisWorker.KEY_PROGRESS, 0) ?: 0,
                w?.outputData?.getString(AnalysisWorker.KEY_ERROR)
            )
        }
}
