package com.bigmoe.onedge.sync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sign-in in the browser (authorization code + PKCE, no MSAL, no signing hash): the browser returns to the app via
 * the custom-scheme redirect [REDIRECT_URI]. Needs the app registration with "Personal Microsoft accounts", public
 * client flows allowed and that redirect URI under "Mobile and desktop applications". Only the app folder is requested (Files.ReadWrite.AppFolder), so the app
 * sees nothing else in the OneDrive; on the PC the folder is OneDrive\Apps\<registration name>.
 *
 * Tokens live in filesDir/onedrive_auth.json (app-private).
 */
class OneDriveAuth(private val file: File) {

    @Volatile private var state: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    /** The app's own Azure registration "BigMoe²" (public client, not a secret). */
    val clientId: String get() = CLIENT_ID

    val signedIn: Boolean get() = state.optString("refreshToken").isNotBlank()
    val account: String get() = state.optString("account")

    fun signOut() {
        state = JSONObject()
        save()
    }

    /**
     * Step 1 of the browser sign-in (authorization code + PKCE): the URL to open. The verifier and state wait in the
     * auth file until the redirect comes back (the app process may be gone by then).
     */
    fun authorizeUrl(): String {
        val rnd = java.security.SecureRandom()
        val verifier = b64(ByteArray(48).also { rnd.nextBytes(it) })
        val st = b64(ByteArray(16).also { rnd.nextBytes(it) })
        val challenge = b64(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        state.put("pkceVerifier", verifier).put("pkceState", st)
        save()
        val q = mapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "response_mode" to "query",
            "scope" to SCOPE,
            "state" to st,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "prompt" to "select_account"
        ).entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return "$AUTHORITY/oauth2/v2.0/authorize?$q"
    }

    /** Step 2: the redirect URI the browser handed back; exchanges the code for tokens. */
    fun completeAuthorization(redirect: android.net.Uri) {
        redirect.getQueryParameter("error")?.let { err ->
            throw IOException(redirect.getQueryParameter("error_description") ?: err)
        }
        val code = redirect.getQueryParameter("code") ?: throw IOException("No code in the answer.")
        val verifier = state.optString("pkceVerifier")
        if (verifier.isBlank() || redirect.getQueryParameter("state") != state.optString("pkceState")) {
            throw IOException("Sign-in answer does not match (start the sign-in again).")
        }
        val o = post(
            "token", mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
                "scope" to SCOPE
            )
        )
        if (o.has("error")) throw IOException(o.optString("error_description", o.optString("error")))
        state.remove("pkceVerifier"); state.remove("pkceState")
        takeTokens(o)
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

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
        const val CLIENT_ID = "3c225cad-e138-4683-9c03-68001d6609c8"
        /** Registered in Azure under "Mobile and desktop applications"; caught by [AuthRedirectActivity]. */
        const val REDIRECT_URI = "msal$CLIENT_ID://auth"
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
