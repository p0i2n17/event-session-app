# 📅 動漫展場與活動場次紀錄 App (`event-session-app`)

一款專為動漫展會（FF、PF、CWT、漫博、TGS、acosta! 等）參戰玩家與攝影師打造的 **Android 智慧場次與 Coser 名單管理 App**，結合 **Google Gemini 2.5 Flash 多模態視覺與聯網查證** 以及 **Google ML Kit 雙引擎光學辨識**。

---

## ✨ 核心功能

### 1. 🧠 Gemini AI 智慧場次預告圖解析與聯網查證
- **預告圖批次建檔**：從圖庫選取 Coser 的活動參戰預告圖或限時動態截圖，透過 `Gemini 2.5 Flash` 自動辨識圖片中的所有展會名稱、日期、天數與發文者 IG 帳號。
- **台灣展場常識庫與自動正名**：內建台灣各大動漫電玩展會正規化規則（自動區分單一主場如 `FF47`、`PF40` 與跨區巡迴場如 `CWT台中場`、`acosta!高雄場`），並自動推算展館地點與開場時間。
- **🌐 Google Search 聯網查證與全域校正**：支援單一場次或全列表一鍵呼叫 Gemini + Google Search Grounding 聯網查詢官方最新展期、地點與總天數。

### 2. 📷 Google ML Kit + ZXing 雙引擎掃描歸檔
- **相機即時掃描 QR Code**：展場現場直接掃描名片或手機 QR Code 快速歸檔。
- **圖庫雙引擎智慧辨識**：自動先以 `ML Kit Barcode Scanning` 偵測彩色/特殊 QR Code；若無條碼則自動切換至 `ML Kit 中日英文字辨識 (OCR)`，過濾手機頂部狀態列並精準提取截圖中的 IG 帳號。
- **智慧社群連結解析與跳轉**：自動識別並格式化 `Instagram (@)`、`X / Twitter`、`Linktree`、`Lit.link` 等網址，單擊即可一鍵跳轉開啟對應社群頁面。

### 3. 📋 參戰名單管理與行前提醒
- **多天數左右滑動切換**：支援多日展會（`Day 1`、`Day 2`...）手勢滑動切換與轉場動畫。
- **最愛置頂、完成狀態篩選與照片紀錄**：支援 ❤️ 最愛標記、拍攝完成勾選、同分組上下排序，以及拍攝/選取縮圖預覽（單擊換照、長按放大、雙擊刪除）。
- **🔍 跨場次參戰史全域查詢**：輸入 IG 帳號或角色備註關鍵字，即可一鍵檢索該 Coser 曾出現在哪些歷史場次。
- **⏰ 自動排程推播提醒**：透過 `AlarmManager` 於活動前 7 天與前 1 天早上 09:00 自動發送系統通知提醒。

---

## 🔐 API 金鑰安全設計

- 本專案**絕不在程式碼或設定檔中寫死任何 API Key**。
- 使用者可於 App 首頁右上角點擊 **「⚙️ AI 引擎設定」** 輸入個人的 Google Gemini API Key，金鑰僅儲存在手機端私密 `SharedPreferences`（`Context.MODE_PRIVATE`）中，不會隨原始碼外洩。
- 本地開發環境路徑檔 `local.properties` 已由 [`.gitignore`](.gitignore) 排除。

---

## 🛠️ 技術架構與套件

- **語言與環境**：Kotlin / Android SDK 35 (minSdk 34) / Java 11
- **AI 與機器學習**：
  - `com.google.ai.client.generativeai:generativeai:0.9.0` (Gemini 2.5 Flash Vision & Search Grounding)
  - `com.google.mlkit:barcode-scanning:17.3.0`
  - `com.google.mlkit:text-recognition-chinese:16.0.1`
- **條碼與資料處理**：
  - `com.journeyapps:zxing-android-embedded:4.3.0`
  - `com.google.code.gson:gson:2.10.1`
- **介面設計**：Material Design 3 (`com.google.android.material:material:1.12.0`)
