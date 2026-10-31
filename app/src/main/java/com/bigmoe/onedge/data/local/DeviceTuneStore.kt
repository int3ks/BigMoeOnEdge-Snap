package com.bigmoe.onedge.data.local

import com.bigmoe.onedge.core.DeviceTuner
import com.bigmoe.onedge.core.TuneParam
import com.bigmoe.onedge.core.TunePoint
import com.bigmoe.onedge.core.TuneReport
import com.bigmoe.onedge.core.TuneResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The device tuner's results, one file per model (by its path), and a marker for the value being tested right now:
 * when the app dies during a reload (almost always out of memory), the next start finds the marker and records that
 * value as the one that closed the app, so it is never picked.
 */
class DeviceTuneStore(private val dir: File) {

    private fun fileFor(modelPath: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(modelPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    private val marker get() = File(dir, "testing-now.json")

    suspend fun load(modelPath: String): TuneReport? = withContext(Dispatchers.IO) {
        runCatching { DeviceTuner.fromJson(fileFor(modelPath).readText()) }.getOrNull()
    }

    suspend fun save(report: TuneReport) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val f = fileFor(report.modelPath)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(DeviceTuner.toJson(report))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Before a risky reload: which model, parameter and value. null clears it. */
    suspend fun markTesting(modelPath: String?, param: TuneParam?, value: Int?) = withContext(Dispatchers.IO) {
        if (modelPath == null || param == null || value == null) {
            marker.delete()
        } else {
            dir.mkdirs()
            marker.writeText(JSONObject().put("model", modelPath).put("param", param.name).put("value", value).toString())
        }
    }

    /**
     * At start: a marker left behind means the app died while testing that value. It is written into that model's
     * report as a failed point. Returns (model path, parameter, value) so the screen can say so, or null.
     */
    suspend fun takeInterrupted(): Triple<String, TuneParam, Int>? = withContext(Dispatchers.IO) {
        val text = runCatching { marker.readText() }.getOrNull() ?: return@withContext null
        marker.delete()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val path = o.optString("model").ifBlank { null } ?: return@withContext null
        val param = runCatching { TuneParam.valueOf(o.getString("param")) }.getOrNull() ?: return@withContext null
        val value = o.optInt("value")
        val old = runCatching { DeviceTuner.fromJson(fileFor(path).readText()) }.getOrNull()
            ?: TuneReport(path, File(path).name)
        val prev = old.results[param]
        val failed = TunePoint(value, null, "The app closed while testing this value (most likely out of memory).")
        val points = (prev?.points.orEmpty().filter { it.value != value }) + failed
        val start = prev?.startValue ?: value
        val res = TuneResult(param, start, points, DeviceTuner.best(points, start), System.currentTimeMillis())
        val report = old.copy(results = old.results + (param to res), updatedAt = System.currentTimeMillis())
        dir.mkdirs()
        fileFor(path).writeText(DeviceTuner.toJson(report))
        Triple(path, param, value)
    }
}
