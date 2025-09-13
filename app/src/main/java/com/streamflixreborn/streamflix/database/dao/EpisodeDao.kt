package com.streamflixreborn.streamflix.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.Episode
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes")
    fun getAllForBackup(): List<Episode> // NUOVO: Per l'esportazione

    @Query(
        """
        SELECT episodes.*, (SELECT MAX(e.watchedDate)
                    FROM episodes e
                    WHERE e.tvShow = episodes.tvShow AND e.isWatched = 1) AS watchedDate
        FROM tv_shows
        JOIN episodes ON episodes.id = (
            SELECT e.id 
            FROM episodes e 
            JOIN seasons s ON s.id = e.season
            JOIN (
                SELECT e2.id, s2.number as seasonNumber, e2.number AS episodeNumber
                FROM episodes e2
                JOIN seasons s2 ON s2.id = e2.season
                WHERE e2.tvShow = episodes.tvShow AND e2.isWatched = 1
                ORDER BY s2.number DESC, e2.number DESC
                LIMIT 1
            ) last_watched
            WHERE e.tvShow = tv_shows.id AND (
                (s.number = last_watched.seasonNumber AND e.number > last_watched.episodeNumber) 
                    OR s.number > last_watched.seasonNumber
            )
            ORDER BY s.number, e.number
            LIMIT 1
        )
        WHERE tv_shows.isWatching = 1 AND NOT EXISTS (
            SELECT 1
            FROM episodes e
            WHERE e.tvShow = episodes.tvShow AND e.lastEngagementTimeUtcMillis IS NOT NULL
        )
    """
    )
    fun getNextEpisodesToWatch(): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun getById(id: String): Episode?

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<Episode>

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    fun getByIdsAsFlow(ids: List<String>): Flow<List<Episode>>

    // Nota: Le query seguenti usano `tvShow = :tvShowId` e `season = :seasonId`.
    // Questo implica che i campi `tvShow` e `season` in Episode.kt potrebbero essere
    // usati per memorizzare ID o necessitano di TypeConverter specifici.
    // La corretta gestione di queste relazioni è cruciale per il backup/ripristino.
    @Query("SELECT * FROM episodes WHERE tvShow = :tvShowId ORDER BY season, number")
    fun getByTvShowId(tvShowId: String): List<Episode>

    @Query("SELECT * FROM episodes WHERE tvShow = :tvShowId ORDER BY season, number")
    fun getByTvShowIdAsFlow(tvShowId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE season = :seasonId ORDER BY season, number")
    fun getBySeasonId(seasonId: String): List<Episode>

    @Query("SELECT * FROM episodes WHERE lastEngagementTimeUtcMillis IS NOT NULL ORDER BY lastEngagementTimeUtcMillis DESC")
    fun getWatchingEpisodes(): Flow<List<Episode>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(episode: Episode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(episodes: List<Episode>) // Esistente, corretto per l'importazione

    @Query("SELECT * FROM episodes WHERE tvShow = :tvShowId")
    fun getEpisodesByTvShowId(tvShowId: String): List<Episode>

    @Update
    fun update(episode: Episode)

    @Query("DELETE FROM episodes")
    fun deleteAll() // NUOVO: Per l'importazione

    fun save(episode: Episode) = getById(episode.id)
        ?.let {
            // Per preservare l'ID esistente e unire i campi rilevanti.
            // La logica di merge effettiva è nel modello Episode.
            val mergedEpisode = it.copy() // Crea una copia dell'entità dal DB
            mergedEpisode.merge(episode)  // Unisci i dati dall'episodio in input
            update(mergedEpisode)         // Aggiorna l'entità unita
        }
        ?: insert(episode)

    @Query(
        """
        UPDATE episodes
        SET isWatched = 0
        WHERE id IN (
            SELECT episode.id
            FROM episodes episode
            LEFT JOIN seasons season ON episode.season = season.id
            JOIN (
                  SELECT episode.tvShow AS tvShow, season.number AS seasonNumber, episode.number AS number
                  FROM episodes episode
                  LEFT JOIN seasons season ON episode.season = season.id
                  WHERE episode.id = :id
            ) episode2 ON (episode.tvShow = episode2.tvShow AND (season.number > episode2.seasonNumber OR (season.number = episode2.seasonNumber AND episode.number > episode2.number)))
        )
    """
    )
    fun resetProgressionFromEpisode(id: String)

    @Query("""
    SELECT e.* 
    FROM episodes e
    JOIN seasons s ON s.id = e.season
    WHERE e.tvShow = :tvShowId AND s.number = :seasonNumber
    ORDER BY s.number, e.number
""")
    fun getByTvShowIdAndSeasonNumber(tvShowId: String, seasonNumber: Int): List<Episode>
}
