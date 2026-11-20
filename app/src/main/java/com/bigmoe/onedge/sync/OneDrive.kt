package com.bigmoe.onedge.sync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sign-in with the device code flow (no redirect URI, no signing hash, no MSAL): the app shows a code, the user
 * enters it at microsoft.com/devicelogin. Needs an own Azure app registration with "Personal Microsoft accounts"
 * and "Allow public client flows" = yes. Only the app folder is requested (Files.ReadWrite.AppFolder), so the app
 * sees nothing else in the OneDrive; on the PC the folder is OneDrive\Apps\<registration name>.
 *
 * Tokens live in filesDir/onedrive_auth.json (app-private).
 */
class OneDriveAuth(private val file: File) {

    data class DeviceCode(val userCode: String, val deviceCode: String, val verificationUri: String, val intervalS: Int, val expiresAtMs: Long)

    @Volatile private var state: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    var clientId: String
        get() = state.optString("clientId")
        set(value) {
            if (value.trim() != clientId) {
                state = JSONObject().put("clientId", value.trim()) // a new registration: old tokens are useless
                save()
            }
        }

    val signedIn: Boolean get() = state.optString("refreshToken").isNotBlank()
    val account: String get() = state.optString("account")

    fun signOut() {
        state = JSONObject().put("clientId", clientId)
        save()
    }

    fun startDeviceCode(): DeviceCode {
        val o = post("devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
        if (o.has("error")) throw IOException(o.optString("error_description", o.optString("error")))
        return DeviceCode(
            userCode = o.getString("user_code"),
            deviceCode = o.getString("device_code"),
            verificationUri = o.optString("verification_uri", "https://www.microsoft.com/link"),
            intervalS = o.optInt("interval", 5).coerceAtLeast(1),
            expiresAtMs = System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L
        )
    }

    /** One poll: true = signed in, false = still waiting; throws when the code expired or was declined. */
    fun pollDeviceCode(code: DeviceCode): Boolean {
        val o = post(
            "token", mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to code.deviceCode
            )
        )
        when (o.optString("error")) {
            "" -> { takeTokens(o); return true }
            "authorization_pending", "slow_down" -> return false
            else -> throw IOException(o.optString("error_description", o.optString("error")))
        }
    }

    /** A valid access token, refreshed when needed. */
    @Synchronized
    fun accessToken(): String {
        val refresh = state.optString("refreshToken")
        if (refresh.isBlank()) throw IOException("Not signed in to OneDrive.")
        val token = state.optString("accessToken")
        if (token.isNotBlank() && System.currentTimeMillis() < state.optLong("expiresAt") - 120_000) return token
        val o = post("token", mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh, "scope" to SCOPE))
        if (o.has("error")) {
            if (o.optString("error") == "invalid_grant") signOut()
            throw IOException("OneDrive sign-in expired: " + o.optString("error_description", o.optString("error")))
        }
        takeTokens(o)
        return state.getString("accessToken")
    }

    fun rememberAccount(name: String) {
        state.put("account", name); save()
    }

    @Synchronized
    private fun takeTokens(o: JSONObject) {
        state.put("accessToken", o.getString("access_token"))
        state.put("expiresAt", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L)
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { state.put("refreshToken", it) }
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(state.toString())
        tmp.renameTo(file)
    }

    private fun post(endpoint: String, form: Map<String, String>): JSONObject {
        val conn = URL("$AUTHORITY/oauth2/v2.0/$endpoint").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "HTTP ${conn.responseCode}").put("error_description", text.take(300)) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Personal Microsoft accounts (the registration must allow them). */
        const val AUTHORITY = "https://login.microsoftonline.com/consumers"
        const val SCOPE = "https://graph.microsoft.com/Files.ReadWrite.AppFolder offline_access"
    }
}

/** The few Graph calls the sync needs, all inside the app folder (special/approot). */
class OneDriveClient(private val auth: OneDriveAuth) {

    data class Item(val name: String, val tag: String, val size: Long)

    /** Files in approot:/[folder]; empty when the folder does not exist yet. */
    fun list(folder: String): List<Item> {
        val out = ArrayList<Item>()
        var url: String? = "$ROOT:/${enc(folder)}:/children?\$select=name,cTag,eTag,size,file&\$top=500"
        while (url != null) {
            val conn = open(url, "GET")
            try {
                if (conn.responseCode == 404) return emptyList()
                val o = JSONObject(readOk(conn))
                val arr = o.optJSONArray("value")
                if (arr != null) for (i in 0 until arr.length()) {
                    val it = arr.getJSONObject(i)
                    if (!it.has("file")) continue
                    out.add(Item(it.getString("name"), it.optString("cTag").ifBlank { it.optString("eTag") }, it.optLong("size")))
                }
                url = o.optString("@odata.nextLink").ifBlank { null }
            } finally {
                conn.disconnect()
            }
        }
        return out
    }

    /** Text content of approot:/[path], or null when it does not exist. */
    fun download(path: String): String? {
        val conn = open("$ROOT:/${encPath(path)}:/content", "GET")
        conn.instanceFollowRedirects = false
        try {
            when (conn.responseCode) {
                404 -> return null
                in 300..399 -> {
                    // Pre-authenticated download URL: fetched without our token.
                    val location = conn.getHeaderField("Location") ?: throw IOException("Download without location")
                    val d = URL(location).openConnection() as HttpURLConnection
                    try {
                        d.connectTimeout = 15000; d.readTimeout = 60000
                        return readOk(d)
                    } finally {
                        d.disconnect()
                    }
                }
                else -> return readOk(conn)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Writes approot:/[path] (folders are created as needed) and returns the new content tag. */
    fun upload(path: String, text: String): String {
        val conn = open("$ROOT:/${encPath(path)}:/content", "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val o = JSONObject(readOk(conn))
            return o.optString("cTag").ifBlank { o.optString("eTag") }
        } finally {
            conn.disconnect()
        }
    }

    /** Creates approot:/[name] if missing (an existing folder is left alone). */
    fun ensureFolder(name: String) {
        val conn = open("$ROOT/children", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 409 && code !in 200..299) throw IOException("OneDrive: cannot create folder $name (HTTP $code)")
        } finally {
            conn.disconnect()
        }
    }

    fun delete(path: String) {
        val conn = open("$ROOT:/${encPath(path)}", "DELETE")
        try {
            val code = conn.responseCode
            if (code != 404 && code !in 200..299) throw IOException("Delete $path: HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    /** Display name of the signed-in account (needs no extra scope beyond the token's own claims: /me/drive). */
    fun ownerName(): String = runCatching {
        val conn = open("$GRAPH/me/drive?\$select=owner", "GET")
        try {
            JSONObject(readOk(conn)).optJSONObject("owner")?.optJSONObject("user")?.optString("displayName").orEmpty()
        } finally {
            conn.disconnect()
        }
    }.getOrDefault("")

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer " + auth.accessToken())
        }

    private fun readOk(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            throw IOException("OneDrive: HTTP $code ${err.take(300)}")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(p: String) = p.split('/').joinToString("/") { enc(it) }

    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        const val ROOT = "$GRAPH/me/drive/special/approot"
    }
}
