package com.example.a1131746

import android.app.AlertDialog
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream
import com.google.android.material.color.DynamicColors
// 導入 Google ML Kit 相關套件
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private lateinit var spinnerFilter: Spinner
    private lateinit var adapter: CoserAdapter

    private var eventDataMap = mutableMapOf<String, MutableList<CoserInfo>>()
    private var eventDaysKeys = mutableListOf<String>()

    private var currentEventName = ""
    private var currentDayIndex = 0
    private var startX = 0f
    private var startY = 0f

    private var currentEditingItem: CoserInfo? = null
    private var currentSearchQuery = ""
    private var currentFilter = 0

    private val PREF_NAME = "CoserAppPrefsV2"
    private val KEY_EVENT_DATA = "NewEventDataMap"

    // 拍照回傳邏輯
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null && currentEditingItem != null) {
            currentEditingItem?.thumbnailBase64 = bitmapToBase64(bitmap)
            saveData()
            updateList()
        }
    }

    // 從圖庫選照片
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && currentEditingItem != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    currentEditingItem?.thumbnailBase64 = bitmapToBase64(bitmap)
                    saveData()
                    updateList()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "無法載入圖片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 相機掃描 (維持原本設定)
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) addNewCoser(formatIgAccount(result.contents), "相機掃描新增")
    }

    // [終極進化] Google ML Kit 雙引擎圖庫掃描 (支援彩色 QR 與 IG 截圖)
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val image = InputImage.fromFilePath(this, uri)
                val barcodeScanner = BarcodeScanning.getClient()
                // 把原本寫著 TextRecognizerOptions.DEFAULT_OPTIONS 的那行，換成這行：
                val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

                // 引擎 1：先嘗試掃描 QR Code
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val rawValue = barcodes[0].rawValue ?: ""
                            addNewCoser(formatIgAccount(rawValue), "圖庫掃描 (QR碼)")
                        } else {
                            textRecognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    // 用實際圖片尺寸（不再用 text blocks 估算）
                                    val imgH = image.height
                                    val statusBarY = imgH * 0.03  // 狀態列 = 頂部 3%

                                    // 把所有文字行依 Y 座標排序（從上到下）
                                    data class OcrLine(val text: String, val top: Int)
                                    val allLines = mutableListOf<OcrLine>()
                                    for (block in visionText.textBlocks) {
                                        for (line in block.lines) {
                                            val box = line.boundingBox ?: continue
                                            allLines.add(OcrLine(line.text.trim(), box.top))
                                        }
                                    }
                                    allLines.sortBy { it.top }

                                    // 跳過狀態列，找第一個符合 IG 帳號格式的文字
                                    val igHandleRegex = Regex("^[a-zA-Z][a-zA-Z0-9._]{1,29}$")
                                    var bestMatch: String? = null

                                    for (line in allLines) {
                                        if (line.top < statusBarY) continue  // 跳過狀態列

                                        // 清理：去除箭頭、@、空格等
                                        val cleaned = line.text
                                            .replace(Regex("^[^a-zA-Z0-9]+"), "")
                                            .replace(Regex("[^a-zA-Z0-9._]+$"), "")
                                            .replace(" ", "")
                                            .trim()

                                        if (cleaned.length >= 3 && igHandleRegex.matches(cleaned)) {
                                            bestMatch = cleaned.lowercase()
                                            break
                                        }

                                        // 嘗試從文字中提取帳號片段
                                        val match = Regex("[a-zA-Z][a-zA-Z0-9._]{2,29}").find(cleaned)
                                        if (match != null && match.value.length >= 3
                                            && !match.value.matches(Regex("^[0-9.]+$"))) {
                                            bestMatch = match.value.lowercase()
                                            break
                                        }
                                    }

                                    if (bestMatch != null) {
                                        addNewCoser(formatIgAccount(bestMatch), "截圖辨識新增")
                                    } else {
                                        Toast.makeText(this, "找不到有效帳號或 QR Code", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .addOnFailureListener {
                                    Toast.makeText(this, "文字辨識失敗", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "圖片載入失敗", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "無法處理圖片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        tvTitle = findViewById(R.id.tv_title)
        listView = findViewById(R.id.list_cosers)
        etSearch = findViewById(R.id.et_search)
        spinnerFilter = findViewById(R.id.spinner_filter)

        currentEventName = intent.getStringExtra("EVENT_NAME") ?: "展場活動"
        val totalDays = intent.getIntExtra("EVENT_DAYS", 1).coerceAtLeast(1)
        loadData()

        for (day in 1..totalDays) {
            val key = "$currentEventName - Day $day"
            if (!eventDataMap.containsKey(key)) {
                eventDataMap[key] = mutableListOf()
            }
        }

        eventDaysKeys = eventDataMap.keys
            .filter { it.startsWith("$currentEventName - Day ") }
            .sortedBy { it.substringAfterLast("Day ").trim().toIntOrNull() ?: Int.MAX_VALUE }
            .toMutableList()

        // 設定過濾下拉選單 (強制設定白字)
        val filterOptions = arrayOf("全部", "未完成", "已完成")
        val spinnerAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, filterOptions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(android.graphics.Color.WHITE)
                return view
            }
        }
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = spinnerAdapter

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFilter = position
                updateList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateList()

        // 搜尋框即時過濾
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString().lowercase()
                updateList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.btn_scan).setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setOrientationLocked(true); setCaptureActivity(PortraitCaptureActivity::class.java) })
        }

        findViewById<Button>(R.id.btn_gallery).setOnClickListener { pickImageLauncher.launch("image/*") }

        // 手動新增按鈕 (Material 3 版本)
        findViewById<Button>(R.id.btn_manual_add).setOnClickListener {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 32, 64, 16)
            }

            val inputIg = EditText(this).apply {
                hint = "輸入 IG 帳號"
                setSingleLine()
            }
            val inputRole = EditText(this).apply {
                hint = "出角名稱"
                setSingleLine()
            }

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 32)
            inputIg.layoutParams = params

            layout.addView(inputIg)
            layout.addView(inputRole)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("手動新增")
                .setView(layout)
                .setPositiveButton("新增") { _, _ ->
                    if (inputIg.text.isNotBlank()) addNewCoser(formatIgAccount(inputIg.text.toString()), inputRole.text.toString())
                }
                .setNegativeButton("取消", null).show()
        }

        findViewById<Button>(R.id.btn_share).setOnClickListener {
            if (eventDaysKeys.isEmpty()) {
                Toast.makeText(this, "目前沒有任何天數資料可分享", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentKey = eventDaysKeys[currentDayIndex]
            var shareText = "🔥 $currentKey 拍攝名單：\n\n"
            eventDataMap[currentKey]?.forEach {
                val status = if (it.isDone) "[已完成]" else "[待處理]"
                shareText += "$status ${it.ig} - ${it.note}\n"
            }
            startActivity(Intent.createChooser(Intent().apply { action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, shareText); type = "text/plain" }, "分享"))
        }

        // 長按刪除 (暗黑主題對話框)
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedItem = adapter.getItem(position)
            if (selectedItem == null || eventDaysKeys.isEmpty()) return@setOnItemLongClickListener true
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("刪除確認").setMessage("確定刪除 ${selectedItem.ig} 嗎？")
                .setPositiveButton("刪除") { _, _ ->
                    eventDataMap[eventDaysKeys[currentDayIndex]]?.remove(selectedItem)
                    saveData(); updateList()
                }.setNegativeButton("取消", null).show()
            true
        }

        if (intent.getBooleanExtra("AUTO_SCAN_QR", false)) {
            intent.removeExtra("AUTO_SCAN_QR")
            barcodeLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setOrientationLocked(true)
                    setCaptureActivity(PortraitCaptureActivity::class.java)
                }
            )
        }
    }

    private fun addNewCoser(ig: String, note: String) {
        if (eventDaysKeys.isEmpty()) {
            Toast.makeText(this, "目前沒有任何天數資料", Toast.LENGTH_SHORT).show()
            return
        }
        val key = eventDaysKeys[currentDayIndex]
        val list = eventDataMap.getOrPut(key) { mutableListOf() }
        val existing = list.find { it.ig.equals(ig, ignoreCase = true) }
        if (existing != null) {
            if (note.isNotBlank() && existing.note.isBlank()) {
                existing.note = note
                existing.role = note
                saveData()
                updateList()
            }
            Toast.makeText(this, "名單中已有 $ig", Toast.LENGTH_SHORT).show()
            return
        }
        list.add(CoserInfo(ig = ig, role = note, note = note))
        saveData()
        updateList()
    }

    // 滑動切換天數
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y }
            MotionEvent.ACTION_UP -> {
                val diffX = startX - event.x
                val diffY = Math.abs(startY - event.y)
                if (diffY < 150 && diffX > 250 && currentDayIndex < eventDaysKeys.size - 1) {
                    currentDayIndex++
                    resetFilters()
                    updateListWithAnimation(R.anim.slide_in_right, R.anim.slide_out_left)
                } else if (diffY < 150 && diffX < -250 && currentDayIndex > 0) {
                    currentDayIndex--
                    resetFilters()
                    updateListWithAnimation(R.anim.slide_in_left, R.anim.slide_out_right)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetFilters() {
        etSearch.text.clear()
        spinnerFilter.setSelection(0)
    }

    private fun updateList() {
        if (eventDaysKeys.isEmpty()) return
        tvTitle.text = eventDaysKeys[currentDayIndex]

        val fullList = eventDataMap[eventDaysKeys[currentDayIndex]] ?: mutableListOf()
        // 相容舊資料：若 note 為空但 role 有值，自動補回 note
        for (c in fullList) {
            if (c.note.isBlank() && c.role.isNotBlank() && c.role != "未輸入") {
                c.note = c.role
            }
        }
        // 穩定排序：最愛優先 → 未完成優先（同組內保留手動順序）
        fullList.sortWith(compareBy({ !it.isFavorite }, { it.isDone }))

        val filteredList = fullList.filter { item ->
            val matchesSearch = currentSearchQuery.isBlank() ||
                    item.ig.lowercase().contains(currentSearchQuery) ||
                    item.note.lowercase().contains(currentSearchQuery) ||
                    item.role.lowercase().contains(currentSearchQuery)
            val matchesFilter = when (currentFilter) {
                1 -> !item.isDone
                2 -> item.isDone
                else -> true
            }
            matchesSearch && matchesFilter
        }

        adapter = CoserAdapter(filteredList)
        listView.adapter = adapter
    }

    private fun updateListWithAnimation(slideIn: Int, slideOut: Int) {
        val anim = AnimationUtils.loadAnimation(this, slideIn)
        listView.startAnimation(anim)
        updateList()
    }

    private fun saveData() { getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_EVENT_DATA, Gson().toJson(eventDataMap)).apply() }

    private fun loadData() {
        val json = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EVENT_DATA, null)
        if (json != null) eventDataMap = Gson().fromJson(json, object : TypeToken<MutableMap<String, MutableList<CoserInfo>>>() {}.type) ?: mutableMapOf()
    }

    // 智慧網址解析器 (自動辨識 IG, X, Linktree 等)
    private fun formatIgAccount(rawText: String): String {
        return when {
            rawText.contains("instagram.com") -> {
                val handle = rawText.substringAfter("instagram.com/").substringBefore("/").substringBefore("?")
                "@$handle"
            }
            rawText.contains("twitter.com") || rawText.contains("x.com") -> {
                val handle = rawText.substringAfterLast("/").substringBefore("?")
                "🐦 X: @$handle"
            }
            rawText.contains("linktr.ee") -> {
                val handle = rawText.substringAfter("linktr.ee/").substringBefore("?")
                "🔗 Linktree: $handle"
            }
            rawText.contains("lit.link") -> {
                val handle = rawText.substringAfter("lit.link/").substringBefore("?")
                "🔗 Lit.link: $handle"
            }
            rawText.startsWith("http") -> "🌐 $rawText"
            rawText.startsWith("@") -> rawText
            else -> "@$rawText"
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap) = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 50, this) }.let { Base64.encodeToString(it.toByteArray(), Base64.DEFAULT) }
    private fun base64ToBitmap(base64Str: String) = try { Base64.decode(base64Str, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (e: Exception) { null }

    private fun moveItem(item: CoserInfo, direction: Int) {
        if (eventDaysKeys.isEmpty()) return
        val key = eventDaysKeys[currentDayIndex]
        val list = eventDataMap[key] ?: return
        val index = list.indexOf(item)
        if (index < 0) return
        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= list.size) return
        val targetItem = list[newIndex]
        if (targetItem.isFavorite != item.isFavorite || targetItem.isDone != item.isDone) {
            Toast.makeText(this, "無法跨越「最愛」或「完成狀態」分組移動", Toast.LENGTH_SHORT).show()
            return
        }
        list.removeAt(index)
        list.add(newIndex, item)
        saveData()
        updateList()
    }

    inner class CoserAdapter(private val items: List<CoserInfo>) : ArrayAdapter<CoserInfo>(this@MainActivity, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.coser_item, parent, false)
            val item = items[position]

            val tvIgAccount = view.findViewById<TextView>(R.id.tv_ig_account)
            tvIgAccount.text = item.ig
            val tvCharacter = view.findViewById<TextView>(R.id.tv_character)
            if (item.note.isBlank()) {
                tvCharacter.text = "點擊新增備註"
                tvCharacter.setTypeface(null, android.graphics.Typeface.ITALIC)
                tvCharacter.alpha = 0.5f
            } else {
                tvCharacter.text = item.note
                tvCharacter.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvCharacter.alpha = 1.0f
            }
            tvCharacter.minHeight = 36
            val imgCoser = view.findViewById<ImageView>(R.id.img_coser)
            val cbDone = view.findViewById<CheckBox>(R.id.cb_done)
            val btnFavorite = view.findViewById<ImageView>(R.id.btn_favorite)
            val btnMoveUp = view.findViewById<ImageView>(R.id.btn_move_up)
            val btnMoveDown = view.findViewById<ImageView>(R.id.btn_move_down)

            // ❤️ 最愛按鈕
            btnFavorite.setImageResource(
                if (item.isFavorite) R.drawable.ic_heart_filled
                else R.drawable.ic_heart_outline
            )
            btnFavorite.setOnClickListener {
                item.isFavorite = !item.isFavorite
                saveData()
                updateList()
            }

            // ▲▼ 移動按鈕
            btnMoveUp.setOnClickListener { moveItem(item, -1) }
            btnMoveDown.setOnClickListener { moveItem(item, 1) }

            // 智慧連結跳轉 (單擊)
            tvIgAccount.setOnClickListener {
                val urlToOpen = when {
                    item.ig.startsWith("🔗 Linktree:") -> "https://linktr.ee/${item.ig.substringAfter("Linktree:").trim()}"
                    item.ig.startsWith("🔗 Lit.link:") -> "https://lit.link/${item.ig.substringAfter("Lit.link:").trim()}"
                    item.ig.startsWith("🐦 X:") -> "https://x.com/${item.ig.substringAfter("@").trim()}"
                    item.ig.startsWith("🌐 ") -> item.ig.substringAfter("🌐").trim()
                    else -> "https://instagram.com/${item.ig.replace("@", "").trim()}"
                }
                if (urlToOpen.isNotBlank()) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))) }
                    catch (e: Exception) { Toast.makeText(this@MainActivity, "無法開啟連結", Toast.LENGTH_SHORT).show() }
                }
            }

            // 長按帳號 → 編輯
            tvIgAccount.setOnLongClickListener {
                val input = EditText(this@MainActivity).apply {
                    setText(item.ig); setSingleLine(); hint = "輸入新帳號"; setPadding(64, 32, 64, 16)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✂️ 編輯帳號").setView(input)
                    .setPositiveButton("儲存") { _, _ ->
                        val newIg = input.text.toString().trim()
                        if (newIg.isNotBlank()) { item.ig = formatIgAccount(newIg); saveData(); updateList() }
                    }.setNegativeButton("取消", null).show()
                true
            }

            // 點擊備註 → 編輯
            tvCharacter.setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    setText(item.note); setSingleLine(); hint = "輸入備註（例如角色名稱）"; setPadding(64, 32, 64, 16)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("📝 編輯備註").setView(input)
                    .setPositiveButton("儲存") { _, _ ->
                        item.note = input.text.toString().trim(); saveData(); updateList()
                    }.setNegativeButton("取消", null).show()
            }

            // 設定圖片
            if (!item.thumbnailBase64.isNullOrBlank()) imgCoser.setImageBitmap(base64ToBitmap(item.thumbnailBase64!!))
            else imgCoser.setImageResource(android.R.drawable.ic_menu_camera)

            // 圖片手勢
            val gestureDetector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    currentEditingItem = item
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("選擇照片來源")
                        .setItems(arrayOf("📷 拍照", "🖼️ 從圖庫選擇")) { _, which ->
                            when (which) { 0 -> takePictureLauncher.launch(null); 1 -> pickPhotoLauncher.launch("image/*") }
                        }.show()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!item.thumbnailBase64.isNullOrBlank()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("🗑️ 刪除照片").setMessage("確定要刪除這張照片嗎？")
                            .setPositiveButton("刪除") { _, _ -> item.thumbnailBase64 = null; saveData(); updateList() }
                            .setNegativeButton("取消", null).show()
                    }
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    if (!item.thumbnailBase64.isNullOrBlank()) {
                        val bitmap = base64ToBitmap(item.thumbnailBase64!!)
                        if (bitmap != null) {
                            val imageView = ImageView(this@MainActivity).apply {
                                setImageBitmap(bitmap); adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(24, 24, 24, 24)
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("🔍 照片預覽").setView(imageView)
                                .setPositiveButton("關閉", null).show()
                        }
                    }
                }
            })
            imgCoser.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

            cbDone.setOnCheckedChangeListener(null)
            cbDone.isChecked = item.isDone
            cbDone.setOnCheckedChangeListener { _, isChecked -> item.isDone = isChecked; saveData(); updateList() }
            return view
        }
    }
}