package com.bigmoe.onedge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the performance log (core/PerfLog.kt): a model load, a failed load, an answer ("turn") or a run that
 * did not end normally ("crash"). [data] is JSON so new fields need no migration.
 */
@Entity(tableName = "perf_events", indices = [Index("at"), Index("model")])
data class PerfEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "data") val data: String
)
