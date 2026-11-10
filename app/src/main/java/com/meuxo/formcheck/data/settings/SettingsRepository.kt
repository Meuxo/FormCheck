package com.meuxo.formcheck.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meuxo.formcheck.domain.model.CourtSize
import com.meuxo.formcheck.domain.model.Units
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.tracking.Side
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val HAND = stringPreferencesKey("hand")
        val HEIGHT = doublePreferencesKey("height_cm")
        val UNITS = stringPreferencesKey("units")
        val BALL = intPreferencesKey("ball_size")
        val COURT = stringPreferencesKey("court_size")
        val VOICE = booleanPreferencesKey("voice_cues")
        val REACH = doublePreferencesKey("standing_reach_cm")
        val GOAL = intPreferencesKey("weekly_makes_goal")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            onboarded = p[K.ONBOARDED] ?: false,
            shootingHand = p[K.HAND]?.let { runCatching { Side.valueOf(it) }.getOrNull() },
            heightCm = p[K.HEIGHT],
            units = p[K.UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.IMPERIAL,
            ballSize = p[K.BALL] ?: 7,
            courtSize = p[K.COURT]?.let { runCatching { CourtSize.valueOf(it) }.getOrNull() } ?: CourtSize.HIGH_SCHOOL,
            voiceCues = p[K.VOICE] ?: true,
            standingReachCm = p[K.REACH],
            weeklyMakesGoal = p[K.GOAL] ?: 100
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { p ->
            val cur = UserSettings(
                onboarded = p[K.ONBOARDED] ?: false,
                shootingHand = p[K.HAND]?.let { runCatching { Side.valueOf(it) }.getOrNull() },
                heightCm = p[K.HEIGHT],
                units = p[K.UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.IMPERIAL,
                ballSize = p[K.BALL] ?: 7,
                courtSize = p[K.COURT]?.let { runCatching { CourtSize.valueOf(it) }.getOrNull() } ?: CourtSize.HIGH_SCHOOL,
                voiceCues = p[K.VOICE] ?: true,
                standingReachCm = p[K.REACH],
                weeklyMakesGoal = p[K.GOAL] ?: 100
            )
            val n = transform(cur)
            p[K.ONBOARDED] = n.onboarded
            if (n.shootingHand != null) p[K.HAND] = n.shootingHand.name else p.remove(K.HAND)
            if (n.heightCm != null) p[K.HEIGHT] = n.heightCm else p.remove(K.HEIGHT)
            p[K.UNITS] = n.units.name
            p[K.BALL] = n.ballSize
            p[K.COURT] = n.courtSize.name
            p[K.VOICE] = n.voiceCues
            if (n.standingReachCm != null) p[K.REACH] = n.standingReachCm else p.remove(K.REACH)
            p[K.GOAL] = n.weeklyMakesGoal
        }
    }
}
