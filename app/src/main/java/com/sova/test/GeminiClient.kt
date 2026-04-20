package com.sova.test

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    data class Result(val question: String, val correctIndex: Int, val correctText: String, val bbox: IntArray?, val explanation: String)

    suspend fun analyze(screen: Bitmap): Result = withContext(Dispatchers.IO) {
        val b64 = bitmapToBase64Jpeg(screen)
        val prompt = "Ти асистент, що розв'язує тести. Поверни JSON: {\"question\":\"...\", \"correct_index\":0, \"correct_text\":\"...\", \"bbox\":[x,y,w,h], \"explanation\":\"...\"}"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }
        val req = Request.Builder().url("$endpoint?key=$apiKey").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext Result("", -1, "", null, "HTTP ${resp.code}", "")
            val j = JSONObject(resp.body!!.string()).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val obj = JSONObject(j)
            val b = if (!obj.isNull("bbox")) { val arr = obj.getJSONArray("bbox"); intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3)) } else null
            Result(obj.optString("question"), obj.optInt("correct_index", -1), obj.optString("correct_text"), b, obj.optString("explanation"))
        }
    }

    private fun bitmapToBase64Jpeg(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
