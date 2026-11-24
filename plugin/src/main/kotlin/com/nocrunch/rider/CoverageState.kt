package com.nocrunch.rider

import com.nocrunch.rider.engine.CoverageFileDto
import com.nocrunch.rider.engine.CoverageStatusDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engine's latest line coverage and whether coverage is available. Cleared when an engine starts, so dots from an
 * earlier engine (say, before coverage was switched off) never outlive it.
 */
class CoverageState {
    private val _files = MutableStateFlow<Map<String, CoverageFileDto>>(emptyMap())
    private val _status = MutableStateFlow<CoverageStatusDto?>(null)

    /** Line states per absolute file path. */
    val files: StateFlow<Map<String, CoverageFileDto>> = _files.asStateFlow()

    /** Whether coverage is available, once the engine has said; null until then. */
    val status: StateFlow<CoverageStatusDto?> = _status.asStateFlow()

    /** Each update from the engine is the complete set. */
    fun replaceFiles(files: List<CoverageFileDto>) {
        _files.value = files.associateBy { it.path }
    }

    fun setStatus(status: CoverageStatusDto) {
        _status.value = status
    }

    fun reset() {
        _files.value = emptyMap()
        _status.value = null
    }
}
