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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    data class Result(
        val question: String,
        val correctIndex: Int,
        val correctText: String,
        val bbox: IntArray?,
        val explanation: String
    )

    suspend fun analyze(screen: Bitmap): Result = withContext(Dispatchers.IO) {
        val b64 = bitmapToBase64Jpeg(screen, 80)

        val prompt = """
Ти — асистент на онлайн-тесті українською мовою.
На скріншоті — питання і варіанти відповідей.

Завдання:
1. Визнач питання.
2. Обери правильний варіант.
3. Поверни bbox [x, y, width, height] у пікселях скріншота навколо тексту правильного варіанта.
4. Коротке пояснення (1-3 речення).

Відповідай СТРОГО JSON без ```:
{"question":"...","correct_index":0,"correct_text":"...","bbox":[x,y,w,h],"explanation":"..."}

Якщо не знаєш координат — bbox: null.
Якщо на скріні немає тесту — correct_index: -1, explanation: "no_test".
""".trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64)
                    ))
                )
            ))
            put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("responseMimeType", "application/json")
            )
        }

        val req = Request.Builder()
            .url("$endpoint?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext Result("", -1, "", null, "HTTP ${resp.code}")
            }
            parseGeminiResponse(raw)
        }
    }

    private fun parseGeminiResponse(raw: String): Result {
        return try {
            val root = JSONObject(raw)
            val text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val cleaned = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val j = JSONObject(cleaned)

            val bbox: IntArray? = if (j.isNull("bbox")) null else {
                val arr = j.getJSONArray("bbox")
                if (arr.length() == 4)
                    intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
                else null
            }

            Result(
                j.optString("question", ""),
                j.optInt("correct_index", -1),
                j.optString("correct_text", ""),
                bbox,
                j.optString("explanation", "")
            )
        } catch (e: Exception) {
            Result("", -1, "", null, "parse_error: ${e.message}")
        }
    }

    private fun bitmapToBase64Jpeg(bmp: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        val scaled = if (bmp.width > 1280) {
            val ratio = 1280f / bmp.width
            Bitmap.createScaledBitmap(bmp, 1280, (bmp.height * ratio).toInt(), true)
        } else bmp
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
