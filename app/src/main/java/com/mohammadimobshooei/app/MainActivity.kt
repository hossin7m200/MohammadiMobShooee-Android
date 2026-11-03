package com.mohammadimobshooei.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceButton: Button
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("grok_assistant", MODE_PRIVATE) }
    private val keyAlias = "mohammadi_grok_api_key"
    private val speechRequest = 4201
    private val permissionRequest = 4202
    private var previousResponseId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousResponseId = prefs.getString("previous_response_id", null)
        tts = TextToSpeech(this, this)
        buildInterface()
        addMessage("assistant", "سلام. من دستیار هوشمند تو هستم و با مدل Grok 4.7 در حالت سریع کار می‌کنم. برای شروع، کلید API را از تنظیمات وارد کن.")
        if (getApiKey().isNullOrBlank()) showApiKeyDialog()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 19, 27))
            setPadding(16, 14, 16, 12)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "دستیار هوشمند"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val settings = Button(this).apply {
            text = "تنظیمات"
            setOnClickListener { showApiKeyDialog() }
        }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(settings)
        root.addView(top)

        status = TextView(this).apply {
            text = "مدل: Grok 4.7 | سریع"
            textSize = 12f
            setTextColor(Color.rgb(120, 210, 190))
            setPadding(4, 4, 4, 10)
        }
        root.addView(status)

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 8, 2, 8)
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "پیامت را بنویس…"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(14, 8, 14, 8)
            setBackgroundColor(Color.rgb(37, 46, 58))
            minLines = 1
            maxLines = 4
        }
        voiceButton = Button(this).apply {
            text = "🎙"
            contentDescription = "گفتار به متن"
            setOnClickListener { startVoiceInput() }
        }
        sendButton = Button(this).apply {
            text = "ارسال"
            setOnClickListener { sendCurrentMessage() }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(voiceButton)
        composer.addView(sendButton)
        root.addView(composer)
        setContentView(root)
    }

    private fun addMessage(role: String, message: String) {
        val bubble = TextView(this).apply {
            text = (if (role == "user") "شما\n" else "Grok\n") + message
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(14, 11, 14, 11)
            setTextIsSelectable(true)
            setBackgroundColor(if (role == "user") Color.rgb(40, 76, 91) else Color.rgb(35, 43, 55))
        }
        val params = LinearLayout.LayoutParams(
            if (role == "user") (resources.displayMetrics.widthPixels * 0.82f).toInt() else -1,
            -2
        )
        params.gravity = if (role == "user") Gravity.END else Gravity.START
        params.setMargins(0, 7, 0, 7)
        chat.addView(bubble, params)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendCurrentMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            showApiKeyDialog()
            return
        }
        input.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        addMessage("user", message)
        setBusy(true)
        executor.execute {
            try {
                val answer = callGrok(apiKey, message)
                runOnUiThread {
                    addMessage("assistant", answer.first)
                    previousResponseId = answer.second
                    prefs.edit().putString("previous_response_id", answer.second).apply()
                    setBusy(false)
                    if (ttsReady) tts?.speak(answer.first, TextToSpeech.QUEUE_FLUSH, null, "grok_reply")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addMessage("assistant", "خطا در ارتباط با Grok: ${e.message ?: "خطای نامشخص"}")
                    setBusy(false)
                }
            }
        }
    }

    private fun callGrok(apiKey: String, message: String): Pair<String, String> {
        val body = JSONObject().apply {
            put("model", "grok-4.7")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", message)
            }))
            put("store", true)
            put("reasoning", JSONObject().put("effort", "low"))
            previousResponseId?.let { put("previous_response_id", it) }
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val connection = (URL("https://api.x.ai/v1/responses").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 30000
        connection.readTimeout = 300000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setFixedLengthStreamingMode(body.size)
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val detail = try { JSONObject(responseText).optString("error", responseText) } catch (_: Exception) { responseText }
                throw IllegalStateException("HTTP $code — $detail")
            }
            val json = JSONObject(responseText)
            val output = json.optJSONArray("output") ?: JSONArray()
            val parts = mutableListOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text") {
                        part.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                    }
                }
            }
            val answer = parts.joinToString("\n").ifBlank { json.optString("output_text") }
            if (answer.isBlank()) throw IllegalStateException("پاسخ متنی از Grok دریافت نشد.")
            return answer to json.optString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        voiceButton.isEnabled = !busy
        status.text = if (busy) "Grok در حال پاسخ‌گویی است…" else "مدل: Grok 4.7 | سریع"
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
            return
        }
        launchSpeechRecognizer()
    }

    private fun launchSpeechRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "صحبت کن")
            }
            startActivityForResult(intent, speechRequest)
        } catch (_: Exception) {
            Toast.makeText(this, "تشخیص گفتار روی این گوشی در دسترس نیست.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input.setText(spoken)
                sendCurrentMessage()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchSpeechRecognizer()
        } else if (requestCode == permissionRequest) {
            Toast.makeText(this, "برای ورود صوتی باید اجازه میکروفون بدهی.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("fa", "IR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun showApiKeyDialog() {
        val field = EditText(this).apply {
            hint = "کلید API از console.x.ai"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getApiKey().orEmpty())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 0)
            addView(TextView(this@MainActivity).apply {
                text = "کلید فقط روی همین گوشی و به‌صورت رمزگذاری‌شده ذخیره می‌شود؛ در مخزن GitHub یا فایل APK ثابت قرار نمی‌گیرد."
                textSize = 13f
            })
            addView(field)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("اتصال به Grok")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotBlank()) {
                    try {
                        saveApiKey(value)
                        previousResponseId = null
                        prefs.edit().remove("previous_response_id").apply()
                        Toast.makeText(this, "کلید روی گوشی ذخیره شد.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "ذخیره امن کلید انجام نشد: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("پاک‌کردن کلید") { _, _ ->
                prefs.edit().remove("key_iv").remove("key_cipher").remove("previous_response_id").apply()
                previousResponseId = null
                Toast.makeText(this, "کلید ذخیره‌شده پاک شد.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun saveApiKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("key_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun getApiKey(): String? {
        val ivText = prefs.getString("key_iv", null) ?: return null
        val cipherText = prefs.getString("key_cipher", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }
}
