# DxVoice-IME 隱私權政策 / Privacy Policy

**最後更新日期 / Last Updated**: 2026-11-24

## 1. 核心隱私承諾（繁體中文）

**DxVoice-IME** 是一款重視使用者隱私的智慧語音輸入法。我們不會建立使用者帳號、不追蹤個人行為、不置入廣告，亦不會將您的語音或文字資料蒐集或販售給任何第三方。

### 1.1 麥克風錄音權限 (`RECORD_AUDIO`)
- 僅在您主動切換至 DxVoice-IME 開始語音輸入時啟用麥克風。
- **離線模式**：所有語音辨識（SenseVoice / Whisper）、自動標點（CT-Transformer）與本地模型修潤（Qwen2.5 0.5B）皆完全於您的裝置端本機執行，音訊與文字不會離開您的手機。
- **雲端／自架端點模式（可選）**：僅當您主動填入自己的 Gemini API Key 或自架端點（BYOK）並啟用雲端修潤時，語音或轉寫文字才會直接傳送至您指定的 API 服務供應商，本開發者不經手亦不儲存任何資料。
- **密碼欄位保護**：偵測到密碼輸入框時會自動略過收音。

### 1.2 無障礙服務權限 (`AccessibilityService` — 可選功能)
- DxVoice-IME 的無障礙服務僅用於以下兩項明確用途：
  1. 偵測系統軟鍵盤（`TYPE_INPUT_METHOD`）開啟／關閉狀態，以自動顯示或隱藏懸浮語音捷徑球。
  2. 當您點擊懸浮語音捷徑球時，協助將系統輸入法直接切換至 DxVoice-IME。
- 本服務**絕不讀取、紀錄、儲存或上傳**您的螢幕內容、對話訊息、密碼或個人資料。

### 1.3 本地資料、自定義詞庫與備份
- 您的設定、自定義詞庫與習慣用語皆儲存於本機裝置。
- 當您主動使用「備份與還原」功能時，備份檔案僅寫入您透過 Android 系統檔案選擇器（SAF）親自指定的本地或 Google 雲端硬碟資料夾。

### 1.4 內購與訂閱
- 所有應用程式內購買與訂閱交易皆透過 **Google Play 帳單系統（Google Play Billing）** 安全處理，本應用程式不會存取或儲存您的信用卡或付款資訊。

---

## 2. Privacy Policy (English)

**DxVoice-IME** is a privacy-first voice input method (IME). We do not require accounts, display ads, track users, or collect/sell personal data.

- **Microphone (`RECORD_AUDIO`)**: Used solely while actively dictating in DxVoice-IME. On-device models (SenseVoice, Whisper, CT-Transformer, Qwen2.5 0.5B) run completely offline on your device. Password fields are automatically skipped. Optional cloud endpoints (Gemini / custom OpenAI-compatible BYOK) communicate directly between your device and the provider you configure.
- **Accessibility Service (Optional)**: Used exclusively to (1) detect when an external soft keyboard opens or closes so the floating voice shortcut appears only alongside the keyboard, and (2) switch the active input method directly to DxVoice-IME when you tap the floating button. We **never** read, log, store, or transmit screen text, messages, or personal content.
- **Backup & Custom Dictionary**: Stored locally on your device. Backups are written only to the local or Google Drive folder you explicitly choose via Android Storage Access Framework (SAF).
- **Payments**: In-app purchases and subscriptions are processed securely via Google Play Billing.

## 3. 聯絡方式 / Contact
如有任何隱私權疑問，請透過 GitHub 專案頁面聯繫開發者：
https://github.com/dx9901mk5/DxVoice-IME/issues
