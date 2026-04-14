package com.meuxo.formcheck.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun getSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY timestamp ASC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
