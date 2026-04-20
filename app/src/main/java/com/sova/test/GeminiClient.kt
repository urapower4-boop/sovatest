package com.sova.test

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

    private val systemPrompt = """
Ти — асистент, який аналізує скріншот тесту (всеосвіта / на урок / Google Forms тощо).
ЗАВДАННЯ: знайти питання, варіанти відповідей та повернути СТРОГИЙ JSON без додаткового тексту.

Типи питань: "single" (один варіант), "multi" (кілька), "order" (хронологія/послідовність), "text" (вписати відповідь), "match" (підібрати пари).

Формат відповіді — ЛИШЕ JSON:
{
  "type": "single|multi|order|text|match",
  "question": "текст питання",
  "explanation": "коротко 1-2 речення чому саме ця відповідь",
  "answers": [
    {"label":"A","text":"варіант","correct":true,"order":1,"bbox":[x1,y1,x2,y2]}
  ],
  "text_answer": "для type=text — сама відповідь",
  "confidence": 0.0
}

bbox — координати у пікселях відносно всього скріншоту (x1,y1 — верхній лівий, x2,y2 — нижній правий).
Для order — order=1,2,3,... у правильній хронологічній послідовності.
Для multi — correct=true для всіх правильних.
Якщо не впевнений — познач confidence < 0.6.
Відповідай ТІЛЬКИ JSON, без markdown, без ```.
""".trimIndent()

    fun solve(bitmap: Bitmap): JSONObject {
        val baos = ByteArr
