package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state
    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.LoadingServers)
        try {
            val servers = UserPreferences.currentProvider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "getServers: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            video.subtitles
                .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                ?.default = true

            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "getVideo: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    private fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.LoadingSubtitles)
        try {
            val subtitles = when (videoType) {
                is Video.Type.Episode -> OpenSubtitles.search(
                    query = videoType.tvShow.title,
                    season = videoType.season.number,
                    episode = videoType.number,
                )
                is Video.Type.Movie -> OpenSubtitles.search(query = videoType.title)
            }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
            _state.emit(State.SuccessLoadingSubtitles(subtitles))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "getSubtitles: ", e)
            _state.emit(State.FailedLoadingSubtitles(e))
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            _state.emit(State.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "downloadSubtitle: ", e)
            _state.emit(State.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
        data object LoadingSubtitles : State()
        data class SuccessLoadingSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : State()
        data class FailedLoadingSubtitles(val error: Exception) : State()
        data object DownloadingOpenSubtitle : State()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : State()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : State()
    }
}
