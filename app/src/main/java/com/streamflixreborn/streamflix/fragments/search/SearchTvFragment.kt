package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchTvBinding
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.hideKeyboard
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class SearchTvFragment : Fragment() {

    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }

    private var appAdapter = AppAdapter()

    private lateinit var voiceHelper: VoiceRecognitionHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SearchViewModel.State.Searching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        binding.vgvSearch.adapter = AppAdapter().also {
                            appAdapter = it
                        }
                    }
                    SearchViewModel.State.SearchingMore -> appAdapter.isLoading = true
                    is SearchViewModel.State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.etSearch.nextFocusDownId = binding.vgvSearch.id
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SearchViewModel.State.FailedSearching -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener {
                                    viewModel.search(viewModel.query)
                                }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.etSearch.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.etSearch.id
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }


    private fun initializeSearch() {
        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_SEARCH,
                    EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_DONE -> {
                        viewModel.search(text.toString())
                        hideKeyboard()
                        true
                    }
                    else -> false
                }
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                   if(s.isNullOrBlank()){
                       binding.etSearch.hint = getString(R.string.search_input_hint)
                   }

                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.hint = getString(R.string.search_input_hint)
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(blink)
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )

        binding.btnSearchVoice.apply {
            requestFocus()
            visibility =
                if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE

            setOnClickListener {
                if (!voiceHelper.isListening) {
                    voiceHelper.startWithPermissionCheck()
                }
            }
        }


        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.hint = getString(R.string.search_input_hint)
            viewModel.search("")
        }

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.search_spacing).toInt())
        }

        binding.root.requestFocus()
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        binding.vgvSearch.apply {
            setNumColumns(
                if (viewModel.query == "") 5
                else 6
            )
        }

        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}