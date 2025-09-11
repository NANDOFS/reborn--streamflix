package com.tanasi.streamflix.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tanasi.streamflix.models.TvShow
import kotlinx.coroutines.flow.Flow

@Dao
interface TvShowDao {

    @Query("SELECT * FROM tv_shows")
    fun getAllForBackup(): List<TvShow> // NUOVO: Sincrono per l'esportazione

    @Query("SELECT * FROM tv_shows WHERE id = :id")
    fun getById(id: String): TvShow?

    @Query("SELECT * FROM tv_shows WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<TvShow?>

    @Query("SELECT * FROM tv_shows WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): Flow<List<TvShow>>

    @Query("SELECT * FROM tv_shows WHERE isFavorite = 1")
    fun getFavorites(): Flow<List<TvShow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(tvShow: TvShow)

    @Update
    fun update(tvShow: TvShow)

    @Insert(onConflict = OnConflictStrategy.REPLACE) // MODIFICATO: OnConflictStrategy e rimozione suspend (se non necessario)
    fun insertAll(tvShows: List<TvShow>)

    @Query("SELECT * FROM tv_shows")
    fun getAll(): Flow<List<TvShow>> // Esistente, mantenuto

    @Query("SELECT * FROM tv_shows WHERE poster IS NULL or poster = ''")
    suspend fun getAllWithNullPoster(): List<TvShow>

    @Query("SELECT id FROM tv_shows")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM tv_shows WHERE LOWER(title) LIKE '%' || :query || '%' LIMIT :limit OFFSET :offset")
    suspend fun searchTvShows(query: String, limit: Int, offset: Int): List<TvShow>

    @Query("DELETE FROM tv_shows")
    fun deleteAll() // NUOVO: Per l'importazione

    fun save(tvShow: TvShow) = getById(tvShow.id)
        ?.let { update(tvShow.copy(id = it.id)) } // Assicurati che l'update usi l'ID corretto
        ?: insert(tvShow)
}
