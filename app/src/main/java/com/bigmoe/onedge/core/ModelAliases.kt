package com.bigmoe.onedge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User-chosen display names for models (the file is never touched), keyed by the model's file name because that is
 * what chats and run details store. [ModelManager] publishes the map whenever the model list or an alias changes.
 */
object ModelAliases {
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map

    fun publish(byFileName: Map<String, String>) { _map.value = byFileName }

    fun display(fileName: String): String = _map.value[fileName] ?: fileName
}
