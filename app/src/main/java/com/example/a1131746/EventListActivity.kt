package com.example.a1131746

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class EventInfo(
    val name: String,
    val location: String,
    val date: String,
    val startTime: String,
    val totalDays: Int
)

data class PendingCoserEntry(
    val eventName: String,
    val loc: String,
    val dateStr: String,
    val startTime: String,
    val totalDays: Int,
    val ig: String,
    val selectedDays: MutableList<Int>
)

class EventListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var eventList = mutableListOf<EventInfo>()
    private var eventDataMap = mutableMapOf<String, MutableList<CoserInfo>>()

    private val PREF_NAME = "CoserAppPrefsV2"
    private val KEY_API_TOKEN = "GeminiApiKey"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun getSmartLocation(name: String, defaultLoc: String): String {
        val upper = name.uppercase()
        val loc = defaultLoc.replace("null", "", ignoreCase = true).trim()

        if (upper.contains("FF") || upper.contains("PF")) return "台北花博爭艷館"
        if (upper.contains("漫博") || upper.contains("台北國際動漫節")) return "台北世貿一館或南港展覽館"
        if (upper.contains("台中") && upper.contains("動漫節")) return "臺中國際展覽館"
        if (upper.contains("高雄") && upper.contains("動漫節")) return "高雄巨蛋"
        if (upper.contains("CF") || upper.contains("COSFORCE")) return "RED SPACE紅點商務中心"
        if (upper.contains("COSMOS")) return "台大體育館 3F"
        if (upper.contains("TGS") || upper.contains("台北國際電玩展")) return "台北南港展覽館一館"
        if (upper.contains("C108") || upper.contains("C109") || upper.contains("C110")) return "日本東京國際展示場 (Tokyo Big Sight)"
        if (upper.contains("OI")) return "未知"

        return if (loc.isBlank()) "未知" else loc
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        if (originalWidth <= maxDimension && originalHeight <= maxDimension) return bitmap

        val ratio = originalWidth.toFloat() / originalHeight.toFloat()
        val (newWidth, newHeight) = if (originalWidth > originalHeight) {
            maxDimension to (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            (maxDimension * ratio).toInt().coerceAtLeast(1) to maxDimension
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private val smartScanLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Toast.makeText(this, "🧠 圖片解析中，請稍候...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val rawBitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    val scaledBitmap = scaleBitmapDown(rawBitmap, 1024)
                    callGeminiVisionAgent(scaledBitmap)
                } catch (e: Exception) {
                    Log.e("ImageError", "載入圖片發生錯誤", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EventListActivity, "圖片載入失敗：${e.localizedMessage ?: "未知錯誤"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_list)

        listView = findViewById(R.id.list_events)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        loadData()
        updateList()

        findViewById<Button>(R.id.btn_smart_scan).setOnClickListener { smartScanLauncher.launch("image/*") }
        findViewById<View>(R.id.fab_add).setOnClickListener {
            showEditConfirmDialog("", "", "", "10:30", 1, "", 1, originalEventName = null, isManualEdit = true)
        }

        // 📷 掃 QR Code 按鈕：若已有場次則讓使用者選擇場次並直接開啟掃描器
        findViewById<Button>(R.id.btn_scan_qr_menu).setOnClickListener {
            if (eventList.isEmpty()) {
                Toast.makeText(this, "請先新增場次後再使用掃描功能", Toast.LENGTH_SHORT).show()
            } else if (eventList.size == 1) {
                val event = eventList[0]
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("EVENT_NAME", event.name)
                    putExtra("EVENT_DAYS", event.totalDays)
                    putExtra("AUTO_SCAN_QR", true)
                })
            } else {
                val eventNames = eventList.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("📷 選擇要掃描歸檔的場次")
                    .setItems(eventNames) { _, which ->
                        val event = eventList[which]
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra("EVENT_NAME", event.name)
                            putExtra("EVENT_DAYS", event.totalDays)
                            putExtra("AUTO_SCAN_QR", true)
                        })
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        // 🔍 參戰史查詢按鈕：全域搜尋所有場次中的 Coser
        findViewById<Button>(R.id.btn_global_search).setOnClickListener {
            val input = EditText(this).apply {
                hint = "輸入 IG 帳號或備註關鍵字"
                setSingleLine()
                setPadding(64, 32, 64, 16)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("🔍 參戰史查詢")
                .setView(input)
                .setPositiveButton("搜尋") { _, _ ->
                    val query = input.text.toString().trim().lowercase()
                    if (query.isBlank()) {
                        Toast.makeText(this, "請輸入搜尋關鍵字", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val results = mutableListOf<String>()
                    for ((key, cosers) in eventDataMap) {
                        for (coser in cosers) {
                            if (coser.ig.lowercase().contains(query) || coser.note.lowercase().contains(query)) {
                                val notePart = if (coser.note.isNotBlank()) " (${coser.note})" else ""
                                results.add("📌 $key → ${coser.ig}$notePart")
                            }
                        }
                    }
                    val msg = if (results.isEmpty()) "找不到符合的紀錄" else results.joinToString("\n")
                    MaterialAlertDialogBuilder(this)
                        .setTitle("搜尋結果")
                        .setMessage(msg)
                        .setPositiveButton("確定", null).show()
                }
                .setNegativeButton("取消", null).show()
        }

        val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        btnSettings.setOnClickListener { showApiSettingDialog() }
        btnSettings.setOnLongClickListener { performGlobalNetworkSync(); true }

        listView.setOnItemClickListener { _, _, position, _ ->
            val event = eventList[position]
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("EVENT_NAME", event.name)
                putExtra("EVENT_DAYS", event.totalDays)
            }
            startActivity(intent)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val event = eventList[position]
            val options = arrayOf("✏️ 編輯與聯網查證", "🗑️ 刪除場次")

            MaterialAlertDialogBuilder(this)
                .setTitle("管理：${event.name}")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        showEditConfirmDialog(
                            name = event.name, loc = event.location, dateStr = event.date,
                            startTime = event.startTime, totalDays = event.totalDays, ig = "", dayIndex = 1,
                            originalEventName = event.name, isManualEdit = true
                        )
                    } else if (which == 1) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("刪除場次")
                            .setMessage("確定要刪除【${event.name}】嗎？這將會一併刪除該場次下所有的 Coser 紀錄！")
                            .setPositiveButton("確定刪除") { _, _ ->
                                cancelEventNotifications(event.name)
                                eventList.removeAt(position)
                                val keysToRemove = eventDataMap.keys.filter { it.startsWith("${event.name} - Day ") }
                                keysToRemove.forEach { eventDataMap.remove(it) }
                                saveData()
                                updateList()
                                Toast.makeText(this, "已刪除 ${event.name}", Toast.LENGTH_SHORT).show()
                            }.setNegativeButton("取消", null).show()
                    }
                }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // 從 MainActivity 返回時重新讀取最新資料，避免覆蓋掉剛在場次內新增/修改的 Coser 名單
        loadData()
        updateList()
    }

    private fun loadData() {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val gson = Gson()

        val listJson = sharedPref.getString("NewEventList", null)
        if (listJson != null) {
            val type = object : TypeToken<MutableList<EventInfo>>() {}.type
            eventList = gson.fromJson(listJson, type) ?: mutableListOf()
        }
        val mapJson = sharedPref.getString("NewEventDataMap", null)
        if (mapJson != null) {
            val type = object : TypeToken<MutableMap<String, MutableList<CoserInfo>>>() {}.type
            eventDataMap = gson.fromJson(mapJson, type) ?: mutableMapOf()
        }
    }

    private fun callGeminiVisionAgent(bitmap: Bitmap) {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val apiKey = sharedPref.getString(KEY_API_TOKEN, "")?.trim() ?: ""

        if (apiKey.isEmpty()) {
            runOnUiThread { Toast.makeText(this, "請先點右上角設定 Gemini API Key", Toast.LENGTH_SHORT).show() }
            return
        }

        val generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )
        )

        val systemPrompt = """
            任務：從這張圖片中擷取「所有」動漫與電玩展資訊，以及圖片頂端或內文中的發文者 IG 帳號。

            【台灣展場常識庫與「正名」規則】（請嚴格遵守）：
            1. 🛡️ 智慧正名與分區判斷 (Normalization)：
               - 【單一/常規地點活動】(如 FF, PF, COSMOS, TGS, 漫博, 漫節, CF)：這類活動原則上只在台北舉辦或有固定主場。若有加上多餘地名，請去除！
                 * 例如：「台北FF47」正名為「FF47」
                 * 例如：「台北漫博」正名為「漫博」
                 * 例如：「台北PF40」正名為「PF40」
                 * 例如：「COSMOS台北場」正名為「COSMOS」
                 * 例如：「台北TGS」或「台北國際電玩展」正名為「TGS(台北國際電玩展)」
               - 【跨區巡迴活動】(如 CWT, CCF, acosta!, WS, GJ, 各地區動漫節)：這類活動明確分北中南。請務必「保留或統一加上」地區標示！
                 * 例如：看到「CWT台中」請輸出「CWT台中場」
                 * 例如：看到「CCF高雄」請輸出「CCF高雄場」
                 * 例如：看到「高雄acosta!」請輸出「acosta!高雄場」
                 * 例如：看到「WS46」請依據上下文補上地區輸出如「WS46高雄場」
                 * 例如：「台中國際動漫節」、「高雄國際動漫節」請保留全名，絕對不可以把地名刪掉。
            2. 📍 自動推測時間與地點 (強制覆寫規則：不可只寫「台北」，請依以下知識庫填入完整展館名稱)：
               - FF系列 / PF系列：台北花博爭艷館 (10:30)
               - COSMOS系列：台大體育館 3F (10:00)
               - TGS(台北國際電玩展)：台北南港展覽館一館 (09:00)
               - 漫博 / 台北國際動漫節：台北世貿一館或南港展覽館 (10:00)
               - 台中國際動漫節：臺中國際展覽館 (10:00)
               - 高雄國際動漫節：高雄巨蛋 (10:00)
               - acosta!台北場：台北松山文創園區 (10:00)
               - acosta!高雄場：高雄駁二藝術特區或高流 (10:00)
               - CWT台北場：台大體育館 (10:30)
               - CWT台中場：台中世貿 (10:30)
               - CWT高雄場：高雄國際會議中心或左營新光 (11:00)
               - CCF台中場：台中逢甲大學育樂館 (10:00)
               - CCF高雄場：高雄展覽館 (10:00)
               - WS系列 (台灣同人誌販售會-南方)：高雄國際會議中心 (10:30)
               - GJ系列 (動漫創作祭)：台中文化創意產業園區 (10:30)
               - 不在清單上或無法確定者，地點填「未知」
            3. 📅 智慧日期推算與防呆：
               - 推算總天數 (例如 8/21-8/23 是 3 天，8/15 是 1 天)。
               - 🔒 鎖定年份：只要未標明年份，一律強制填寫 2026 年。
            4. 👤 IG 帳號擷取：
               - 請仔細檢查圖片最上方（IG 限時動態或貼文頭像右側的英數帳號字串）或內文中的 @帳號，填入每個場次的 `ig` 欄位。
            5. 合併同一個活動的資訊為單一筆。嚴格輸出為 JSON 陣列 (Array)，不要加 ```json 標籤。

            格式：
            [ { "name": "活動名稱(依正名規則)", "date": "YYYY-MM-DD", "location": "地點", "ig": "帳號含@", "totalDays": 數字, "startTime": "HH:mm" } ]
        """.trimIndent()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = generativeModel.generateContent(content { image(bitmap); text(systemPrompt) })
                val text = response.text ?: ""
                Log.d("GeminiVision", text)

                var cleanJson = text.replace("```json", "").replace("```", "").trim()
                val startIndex = cleanJson.indexOfFirst { it == '{' || it == '[' }
                val endIndex = cleanJson.indexOfLast { it == '}' || it == ']' }

                if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                    cleanJson = cleanJson.substring(startIndex, endIndex + 1)

                    var masterIg = ""
                    val jsonArray: JSONArray

                    if (cleanJson.startsWith("{")) {
                        val rootObj = JSONObject(cleanJson)
                        masterIg = rootObj.optString("author_ig", "").trim()
                        jsonArray = rootObj.optJSONArray("events") ?: JSONArray()
                    } else {
                        jsonArray = JSONArray(cleanJson)
                        for (i in 0 until jsonArray.length()) {
                            val tempObj = jsonArray.optJSONObject(i) ?: continue
                            val tempIg = tempObj.optString("ig", "").trim()
                            if (tempIg.isNotBlank() && !tempIg.equals("null", ignoreCase = true) && tempIg != "@") {
                                masterIg = tempIg
                                break
                            }
                        }
                    }

                    if (masterIg.equals("null", ignoreCase = true) || masterIg == "@") masterIg = ""
                    if (masterIg.isNotBlank() && !masterIg.startsWith("@")) masterIg = "@$masterIg"

                    withContext(Dispatchers.Main) {
                        val pendingEntries = mutableListOf<PendingCoserEntry>()

                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.optJSONObject(i) ?: continue
                            val name = obj.optString("name").trim()
                            if (name.isBlank() || name.equals("null", true)) continue

                            var eventIg = obj.optString("ig", "").trim()
                            if (eventIg.equals("null", ignoreCase = true) || eventIg == "@") eventIg = ""
                            if (eventIg.isBlank()) eventIg = masterIg
                            if (eventIg.isNotBlank() && !eventIg.startsWith("@")) eventIg = "@$eventIg"

                            val finalLoc = getSmartLocation(name, obj.optString("location", "未知"))
                            val totalDays = obj.optInt("totalDays", 1).coerceAtLeast(1)

                            pendingEntries.add(PendingCoserEntry(
                                eventName = name,
                                loc = finalLoc,
                                dateStr = obj.optString("date", "").replace("null", "", true),
                                startTime = obj.optString("startTime", "10:30").replace("null", "", true),
                                totalDays = totalDays,
                                ig = eventIg,
                                selectedDays = (1..totalDays).toMutableList()
                            ))
                        }

                        if (pendingEntries.isEmpty()) {
                            Toast.makeText(this@EventListActivity, "AI 未辨識出任何場次", Toast.LENGTH_SHORT).show()
                        } else {
                            showDaySelectionDialog(pendingEntries)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(this@EventListActivity, "AI 回傳格式錯誤", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e("GeminiError", "辨識失敗", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EventListActivity, "AI 辨識失敗：${e.localizedMessage ?: "網路斷線或 API Key 異常"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showEditConfirmDialog(
        name: String,
        loc: String,
        dateStr: String,
        startTime: String,
        totalDays: Int,
        ig: String,
        dayIndex: Int,
        originalEventName: String? = null,
        isManualEdit: Boolean = false
    ) {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        scrollView.addView(layout)

        val inputName = EditText(this).apply { hint = "場次名稱 (例: FF47)"; setText(name); setSingleLine() }
        val inputLoc = EditText(this).apply { hint = "地點"; setText(loc); setSingleLine() }
        val inputDate = EditText(this).apply { hint = "日期 (YYYY-MM-DD)"; setText(dateStr); setSingleLine() }
        val inputTime = EditText(this).apply { hint = "開始時間 (HH:mm)"; setText(startTime); setSingleLine() }
        val inputDays = EditText(this).apply { hint = "總天數"; setText(totalDays.toString()); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val inputIg = EditText(this).apply { hint = "新增 IG 帳號 (選填)"; setText(ig); setSingleLine() }

        val btnSearch = Button(this).apply {
            text = "🌐 呼叫 AI 聯網查證"
            setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
            setTextColor(android.graphics.Color.WHITE)

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val marginPx = (12 * resources.displayMetrics.density).toInt()
            params.setMargins(0, marginPx, 0, marginPx)
            layoutParams = params

            setOnClickListener {
                val queryName = inputName.text.toString().trim()
                if (queryName.isNotEmpty()) {
                    isEnabled = false
                    setBackgroundColor(android.graphics.Color.GRAY)
                    text = "⏳ 呼叫雲端中..."
                    Toast.makeText(this@EventListActivity, "啟動搜尋中...", Toast.LENGTH_SHORT).show()
                    performNetworkSearch(queryName, inputLoc, inputDate, inputTime, inputDays, this)
                } else {
                    Toast.makeText(this@EventListActivity, "請先輸入場次名稱！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(inputName); layout.addView(btnSearch); layout.addView(inputLoc)
        layout.addView(inputDate); layout.addView(inputTime); layout.addView(inputDays); layout.addView(inputIg)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (originalEventName != null) "編輯場次資料" else "新增場次")
            .setView(scrollView)
            .setPositiveButton("儲存並建檔") { _, _ ->
                val finalDays = (inputDays.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1)
                saveEventAndCoser(
                    name = inputName.text.toString().trim(),
                    loc = inputLoc.text.toString().trim(),
                    dateStr = inputDate.text.toString().trim(),
                    startTime = inputTime.text.toString().trim(),
                    totalDays = finalDays,
                    ig = inputIg.text.toString().trim(),
                    dayIndex = dayIndex,
                    shouldSave = true,
                    originalEventName = originalEventName,
                    isManualEdit = isManualEdit
                )
                updateList()
            }.setNegativeButton("取消", null).show()
    }

    private fun performNetworkSearch(
        eventName: String, inputLoc: EditText, inputDate: EditText,
        inputTime: EditText, inputDays: EditText, btnSearch: Button
    ) {
        val sharedPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val geminiApiKey = sharedPref.getString(KEY_API_TOKEN, "")?.trim() ?: ""
        if (geminiApiKey.isEmpty()) {
            Toast.makeText(this, "請先點右上角設定 Gemini API Key", Toast.LENGTH_SHORT).show()
            btnSearch.isEnabled = true
            btnSearch.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
            btnSearch.text = "🌐 呼叫 AI 聯網查證"
            return
        }

        val prompt = """
            請上網搜尋台灣的展會活動：「$eventName」。找出最新一屆的官方資訊。
            嚴格輸出為 JSON 格式，不要加 ```json 標籤：
            {"location": "準確的地點","date": "YYYY-MM-DD","startTime": "HH:mm","totalDays": 數字}
        """.trimIndent()

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey}")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 20000

                val requestObj = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("tools", JSONArray().apply { put(JSONObject().apply { put("googleSearch", JSONObject()) }) })
                }

                connection.outputStream.use { os ->
                    val input = requestObj.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val rootObj = JSONObject(responseStr)
                    val text = rootObj.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.optJSONObject(0)?.optString("text", "") ?: ""

                    var cleanedText = text.replace("```json", "").replace("```", "").trim()
                    val jsonStart = cleanedText.indexOf('{')
                    val jsonEnd = cleanedText.lastIndexOf('}')
                    if (jsonStart == -1 || jsonEnd == -1 || jsonStart > jsonEnd) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@EventListActivity, "AI 回傳格式錯誤", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    cleanedText = cleanedText.substring(jsonStart, jsonEnd + 1)
                    val jsonObject = JSONObject(cleanedText)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EventListActivity, "✅ 查證完畢！", Toast.LENGTH_SHORT).show()

                        val loc = getSmartLocation(eventName, jsonObject.optString("location", ""))
                        val date = jsonObject.optString("date", "").replace("null", "", true)
                        val time = jsonObject.optString("startTime", "").replace("null", "", true)
                        val days = jsonObject.optInt("totalDays", 0)

                        if (loc.isNotBlank() && loc != "未知") inputLoc.setText(loc)
                        if (date.isNotBlank()) inputDate.setText(date)
                        if (time.isNotBlank()) inputTime.setText(time)
                        if (days > 0) inputDays.setText(days.toString())
                    }
                } else {
                    val errStr = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.e("GeminiSearch", "HTTP $code: $errStr")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EventListActivity, "查證失敗 (HTTP $code)，請檢查 API Key 或額度", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("GeminiSearch", "聯網失敗", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@EventListActivity, "聯網失敗：${e.localizedMessage ?: "請檢查網路"}", Toast.LENGTH_SHORT).show() }
            } finally {
                connection?.disconnect()
                withContext(Dispatchers.Main) {
                    btnSearch.isEnabled = true
                    btnSearch.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                    btnSearch.text = "🌐 呼叫 AI 聯網查證"
                }
            }
        }
    }

    private fun performGlobalNetworkSync() {
        if (eventList.isEmpty()) {
            Toast.makeText(this, "列表是空的，不需要校正！", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val geminiApiKey = sharedPref.getString(KEY_API_TOKEN, "")?.trim() ?: ""
        if (geminiApiKey.isEmpty()) {
            Toast.makeText(this, "請先點右上角設定 Gemini API Key", Toast.LENGTH_SHORT).show()
            return
        }
        val eventNames = eventList.joinToString(", ") { it.name }

        val prompt = """
            上網搜尋以下台灣展會活動的 2026 年官方資訊：
            【 $eventNames 】
            
            【極度嚴格警告】：
            1. 絕對不可以擅自把簡稱展開！(例如：原本是「漫博」，newName 必須是「漫博」)。
            2. 僅限去除多餘地名 (例如「台北FF47」正名為「FF47」)。
            3. 如果找不到確切地點或時間，請直接填 "" (空字串)，絕對不可填寫 "null"！
            
            嚴格輸出 JSON 陣列：
            [ { "originalName": "...", "newName": "...", "location": "...", "date": "...", "startTime": "...", "totalDays": 數字 } ]
        """.trimIndent()

        Toast.makeText(this, "🌐 啟動全域校正，可能需要 30~60 秒...", Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey}")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                val requestObj = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("tools", JSONArray().apply { put(JSONObject().apply { put("googleSearch", JSONObject()) }) })
                }

                connection.outputStream.use { os ->
                    val input = requestObj.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val rootObj = JSONObject(responseStr)

                    val text = rootObj.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.optJSONObject(0)?.optString("text", "") ?: ""

                    val startIndex = text.indexOf('[')
                    val endIndex = text.lastIndexOf(']')

                    if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                        val cleanJson = text.substring(startIndex, endIndex + 1)
                        val jsonArray = JSONArray(cleanJson)

                        withContext(Dispatchers.Main) {
                            var updateCount = 0
                            var isListModified = false

                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.optJSONObject(i) ?: continue
                                val originalName = obj.optString("originalName").trim()
                                val newName = obj.optString("newName").trim()

                                val targetName = if (newName.isNotBlank()) newName else originalName

                                val aiLoc = getSmartLocation(targetName, obj.optString("location", ""))

                                var aiDate = obj.optString("date").trim()
                                if (aiDate.equals("null", ignoreCase = true)) aiDate = ""

                                var aiTime = obj.optString("startTime").trim()
                                if (aiTime.equals("null", ignoreCase = true)) aiTime = ""

                                val aiDays = obj.optInt("totalDays", 0)

                                if (originalName.isNotBlank() && newName.isNotBlank() && originalName != newName) {
                                    renameAndMergeEvent(originalName, newName)
                                    isListModified = true
                                }

                                val index = eventList.indexOfFirst { it.name.lowercase() == targetName.lowercase() }

                                if (index != -1) {
                                    val oldEvent = eventList[index]

                                    val newLoc = if (aiLoc.isNotBlank() && aiLoc != "未知") aiLoc else oldEvent.location
                                    val newDate = if (aiDate.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) aiDate else oldEvent.date
                                    val newTime = if (aiTime.isNotBlank() && aiTime != "未知") aiTime else oldEvent.startTime
                                    val newDays = if (aiDays > oldEvent.totalDays) aiDays else oldEvent.totalDays

                                    if (newLoc != oldEvent.location || newDate != oldEvent.date || newTime != oldEvent.startTime || newDays != oldEvent.totalDays) {
                                        val updatedEvent = oldEvent.copy(location = newLoc, date = newDate, startTime = newTime, totalDays = newDays)
                                        eventList[index] = updatedEvent
                                        for (day in 1..newDays) {
                                            val finalKey = "${updatedEvent.name} - Day $day"
                                            if (!eventDataMap.containsKey(finalKey)) eventDataMap[finalKey] = mutableListOf()
                                        }
                                        scheduleEventNotifications(updatedEvent)
                                        updateCount++
                                        isListModified = true
                                    }
                                }
                            }

                            if (isListModified) {
                                saveData()
                                updateList()
                                Toast.makeText(this@EventListActivity, "✨ 更新了 $updateCount 個場次的資訊！", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@EventListActivity, "✅ 所有場次皆已是最新資訊！", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val errStr = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.e("GlobalSync", "HTTP $code: $errStr")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EventListActivity, "全域校正失敗 (HTTP $code)，請檢查 API Key 或額度", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("GlobalSync", "全域校正異常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EventListActivity, "發生錯誤：${e.localizedMessage ?: "網路異常"}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun renameAndMergeEvent(oldName: String, newName: String) {
        val oldEvent = eventList.find { it.name.equals(oldName, ignoreCase = true) } ?: return
        val existingNewEvent = eventList.find { it.name.equals(newName, ignoreCase = true) && it !== oldEvent }

        cancelEventNotifications(oldEvent.name)

        if (existingNewEvent != null) {
            val mergedDays = maxOf(oldEvent.totalDays, existingNewEvent.totalDays)
            for (day in 1..oldEvent.totalDays) {
                val oldKey = "${oldEvent.name} - Day $day"
                val newKey = "${existingNewEvent.name} - Day $day"

                val oldCosers = eventDataMap.remove(oldKey) ?: mutableListOf()
                if (!eventDataMap.containsKey(newKey)) eventDataMap[newKey] = mutableListOf()

                val newCosers = eventDataMap[newKey]!!
                for (coser in oldCosers) {
                    if (newCosers.none { it.ig == coser.ig }) newCosers.add(coser)
                }
            }
            val index = eventList.indexOf(existingNewEvent)
            val updated = existingNewEvent.copy(totalDays = mergedDays)
            eventList[index] = updated
            eventList.remove(oldEvent)
            scheduleEventNotifications(updated)
        } else {
            val index = eventList.indexOf(oldEvent)
            val updated = oldEvent.copy(name = newName)
            eventList[index] = updated
            for (day in 1..oldEvent.totalDays) {
                val oldKey = "${oldEvent.name} - Day $day"
                val newKey = "$newName - Day $day"
                val cosers = eventDataMap.remove(oldKey)
                if (cosers != null) eventDataMap[newKey] = cosers
            }
            scheduleEventNotifications(updated)
        }
    }

    private fun showDaySelectionDialog(pendingEntries: List<PendingCoserEntry>) {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 16, 64, 16)
        }
        scrollView.addView(container)

        val allCheckboxGroups = mutableListOf<Pair<PendingCoserEntry, List<android.widget.CheckBox>>>()

        for (entry in pendingEntries) {
            val titleView = TextView(this).apply {
                text = "📌 ${entry.eventName}"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            container.addView(titleView)

            if (entry.ig.isNotBlank()) {
                val igView = TextView(this).apply {
                    text = "    👤 ${entry.ig}"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#B0B0B0"))
                }
                container.addView(igView)
            }

            if (entry.totalDays > 1) {
                val dayLabel = TextView(this).apply {
                    text = "    加入哪幾天？（共 ${entry.totalDays} 天）"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                }
                container.addView(dayLabel)

                val checkboxes = mutableListOf<android.widget.CheckBox>()
                for (day in 1..entry.totalDays) {
                    val cb = android.widget.CheckBox(this).apply {
                        text = "Day $day"
                        isChecked = true
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.leftMargin = 32
                        layoutParams = lp
                    }
                    container.addView(cb)
                    checkboxes.add(cb)
                }
                allCheckboxGroups.add(entry to checkboxes)
            } else {
                val hint = TextView(this).apply {
                    text = "    → 自動加入 Day 1"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                }
                container.addView(hint)
            }

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    topMargin = (12 * resources.displayMetrics.density).toInt()
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
                setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            }
            container.addView(divider)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📸 AI 辨識結果確認")
            .setView(scrollView)
            .setPositiveButton("確認建檔") { _, _ ->
                for ((entry, checkboxes) in allCheckboxGroups) {
                    entry.selectedDays.clear()
                    for ((dayIdx, cb) in checkboxes.withIndex()) {
                        if (cb.isChecked) entry.selectedDays.add(dayIdx + 1)
                    }
                }

                val newEventsSet = mutableSetOf<String>()
                for (entry in pendingEntries) {
                    var finalSavedName = ""
                    for (day in entry.selectedDays) {
                        finalSavedName = saveEventAndCoser(
                            name = entry.eventName, loc = entry.loc,
                            dateStr = entry.dateStr, startTime = entry.startTime,
                            totalDays = entry.totalDays, ig = entry.ig,
                            dayIndex = day, shouldSave = false
                        )
                    }
                    if (entry.selectedDays.isEmpty()) {
                        finalSavedName = saveEventAndCoser(
                            name = entry.eventName, loc = entry.loc,
                            dateStr = entry.dateStr, startTime = entry.startTime,
                            totalDays = entry.totalDays, ig = "",
                            dayIndex = 1, shouldSave = false
                        )
                    }

                    val daysText = when {
                        entry.selectedDays.size == entry.totalDays -> "全天"
                        entry.selectedDays.isEmpty() -> "僅建場次"
                        else -> entry.selectedDays.joinToString(",") { "D$it" }
                    }
                    val displayIg = if (entry.ig.isNotBlank()) "(👤 ${entry.ig} [$daysText])" else "⚠️無帳號"
                    if (finalSavedName.isNotBlank()) newEventsSet.add("$finalSavedName $displayIg")
                }

                saveData()
                updateList()

                val msg = newEventsSet.joinToString("\n")
                MaterialAlertDialogBuilder(this)
                    .setTitle("🎉 批次建檔成功")
                    .setMessage("已建立以下場次：\n\n$msg")
                    .setPositiveButton("確定", null).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveEventAndCoser(
        name: String,
        loc: String,
        dateStr: String,
        startTime: String,
        totalDays: Int,
        ig: String,
        dayIndex: Int,
        shouldSave: Boolean = true,
        originalEventName: String? = null,
        isManualEdit: Boolean = false
    ): String {
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.equals("null", true)) return ""

        var finalTime = startTime.trim()
        if (finalTime.contains("null", ignoreCase = true) || finalTime.isBlank()) finalTime = "10:30"

        var finalDate = dateStr.trim()
        if (finalDate.contains("null", ignoreCase = true)) finalDate = ""

        val resolvedLoc = getSmartLocation(cleanName, loc)

        // 若為手動編輯且修改了場次名稱，先進行改名與資料遷移
        if (isManualEdit && !originalEventName.isNullOrBlank() && !originalEventName.equals(cleanName, ignoreCase = true)) {
            renameAndMergeEvent(originalEventName, cleanName)
        }

        val existingEventIndex = eventList.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        val finalName = if (existingEventIndex != -1) {
            val oldEvent = eventList[existingEventIndex]
            val newDays = if (isManualEdit) totalDays.coerceAtLeast(1) else maxOf(totalDays, oldEvent.totalDays)
            val newLoc = if (isManualEdit) {
                if (loc.isNotBlank()) loc else resolvedLoc
            } else {
                if (resolvedLoc.isNotBlank() && resolvedLoc != "未知") resolvedLoc else oldEvent.location
            }
            val newDate = if (isManualEdit) {
                finalDate
            } else {
                if (finalDate.isNotBlank() && oldEvent.date.isEmpty()) finalDate else oldEvent.date
            }
            val newTime = if (isManualEdit) {
                finalTime
            } else {
                if (finalTime.isNotBlank() && oldEvent.startTime == "10:30") finalTime else oldEvent.startTime
            }

            val updatedEvent = oldEvent.copy(
                name = if (isManualEdit) cleanName else oldEvent.name,
                location = newLoc,
                date = newDate,
                startTime = newTime,
                totalDays = newDays
            )
            eventList[existingEventIndex] = updatedEvent

            for (day in 1..newDays) {
                val mapKey = "${updatedEvent.name} - Day $day"
                if (!eventDataMap.containsKey(mapKey)) eventDataMap[mapKey] = mutableListOf()
            }
            if (isManualEdit) {
                // 若手動調降天數，移除多餘且空白的天數 Key
                val extraKeys = eventDataMap.keys.filter { key ->
                    if (!key.startsWith("${updatedEvent.name} - Day ")) false
                    else {
                        val dayNum = key.substringAfterLast("Day ").trim().toIntOrNull() ?: 0
                        dayNum > newDays
                    }
                }
                extraKeys.forEach { eventDataMap.remove(it) }
            }
            scheduleEventNotifications(updatedEvent)
            updatedEvent.name
        } else {
            val finalLoc = if (loc.isNotBlank() && loc != "未知") loc else resolvedLoc
            val newEvent = EventInfo(name = cleanName, location = finalLoc, date = finalDate, startTime = finalTime, totalDays = totalDays.coerceAtLeast(1))
            eventList.add(newEvent)
            for (day in 1..newEvent.totalDays) {
                eventDataMap["$cleanName - Day $day"] = mutableListOf()
            }
            scheduleEventNotifications(newEvent)
            cleanName
        }

        if (ig.isNotBlank()) {
            val formattedIg = if (ig.startsWith("@")) ig else "@$ig"
            val listKey = "$finalName - Day $dayIndex"
            val currentList = eventDataMap.getOrPut(listKey) { mutableListOf() }

            if (currentList.none { it.ig.equals(formattedIg, ignoreCase = true) }) {
                currentList.add(CoserInfo(ig = formattedIg, role = "未輸入", thumbnailBase64 = null, isDone = false, note = if (isManualEdit) "" else "AI智慧建檔"))
            }
        }

        if (shouldSave) saveData()
        return finalName
    }

    private fun scheduleEventNotifications(event: EventInfo) {
        if (event.date.isBlank() || !event.date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val parsedDate = try { sdf.parse(event.date) } catch (e: Exception) { null } ?: return

        val now = System.currentTimeMillis()
        val schedules = listOf("WEEK" to -7, "BEFORE" to -1)

        for ((type, dayOffset) in schedules) {
            val cal = Calendar.getInstance().apply {
                time = parsedDate
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val triggerAtMillis = cal.timeInMillis
            val intent = Intent(this, NotificationReceiver::class.java).apply {
                putExtra("EVENT_NAME", event.name)
                putExtra("LOCATION", event.location)
                putExtra("START_TIME", event.startTime)
                putExtra("NOTIFY_TYPE", type)
            }
            val requestCode = (event.name + "_" + type).hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (triggerAtMillis > now) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } catch (e: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else {
                alarmManager.cancel(pendingIntent)
            }
        }
    }

    private fun cancelEventNotifications(eventName: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (type in listOf("WEEK", "BEFORE")) {
            val intent = Intent(this, NotificationReceiver::class.java)
            val requestCode = (eventName + "_" + type).hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }

    private fun saveData() {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        sharedPref.edit().apply {
            putString("NewEventList", gson.toJson(eventList))
            putString("NewEventDataMap", gson.toJson(eventDataMap))
            apply()
        }
    }

    private fun updateList() {
        eventList.sortWith(compareBy<EventInfo> { it.date.isBlank() }.thenBy { it.date })

        listView.adapter = object : ArrayAdapter<EventInfo>(this, R.layout.event_list_item, eventList) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.event_list_item, parent, false)
                val item = eventList[position]

                view.findViewById<TextView>(R.id.item_event_name).text = item.name

                val dateStr = if (item.date.isNotBlank() && !item.date.contains("null", true)) item.date else "日期未定"
                val timeStr = if (item.startTime.isNotBlank() && !item.startTime.contains("null", true)) item.startTime else "未知"
                val locStr = if (item.location.isNotBlank() && !item.location.contains("null", true)) item.location else "未知"

                view.findViewById<TextView>(R.id.item_event_date).text = "📅 $dateStr (共 ${item.totalDays} 天) $timeStr 開始"
                view.findViewById<TextView>(R.id.item_event_location).text = "📍 $locStr"

                return view
            }
        }
    }

    private fun showApiSettingDialog() {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentKey = sharedPref.getString(KEY_API_TOKEN, "")

        val input = EditText(this).apply {
            hint = "輸入 Google Gemini API Key"
            setText(currentKey)
            setSingleLine()
            setPadding(64, 32, 64, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ AI 引擎設定")
            .setView(input)
            .setPositiveButton("儲存") { _, _ ->
                val newKey = input.text.toString().trim()
                sharedPref.edit().putString(KEY_API_TOKEN, newKey).apply()
                Toast.makeText(this, "API Key 已儲存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
