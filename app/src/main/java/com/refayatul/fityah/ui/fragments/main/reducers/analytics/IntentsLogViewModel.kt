package com.refayatul.fityah.ui.fragments.main.reducers.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.db.IntentLogDao
import com.refayatul.fityah.data.db.IntentLogEntity

class IntentsLogViewModel(private val dao: IntentLogDao) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _dateRange = MutableStateFlow<Pair<Long, Long>?>(null)

    val filteredLogs: StateFlow<List<IntentLogEntity>> = combine(
        dao.getAllIntentLogs(),
        _searchQuery,
        _dateRange
    ) { logs, query, dateRange ->
        logs.filter { log ->
            val matchesQuery = if (query.isBlank()) true else {
                log.packageName.contains(query, ignoreCase = true) ||
                log.intentText.contains(query, ignoreCase = true)
            }
            val matchesDate = if (dateRange == null) true else {
                log.timestamp in dateRange.first..dateRange.second
            }
            matchesQuery && matchesDate
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dateRangeText: StateFlow<String> = _dateRange.map { range ->
        if (range == null) "All Time"
        else {
            val start = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(java.util.Date(range.first))
            val end = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(java.util.Date(range.second))
            if (start == end) start else "$start - $end"
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "All Time")

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setDateRange(start: Long, end: Long) {
        _dateRange.value = Pair(start, end)
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _dateRange.value = null
    }

    fun deleteLog(id: Int) {
        viewModelScope.launch {
            dao.delete(id)
        }
    }
}

class IntentsLogViewModelFactory(private val dao: IntentLogDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IntentsLogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return IntentsLogViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
