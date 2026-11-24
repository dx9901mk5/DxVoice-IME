# DxVoice-IME | 離線 AI 智慧語音輸入法

> **支援 Android 手機、平板、摺疊機與 Chromebook 的高速離線 AI 語音輸入法，整合多語離線辨識、自動標點、Qwen2.5 0.5B 本地修潤、自定義詞庫、懸浮語音捷徑球與平板雙欄並排模式。**

## 📲 下載與加入 Google Play 封閉測試

本專案已改為透過 **Google Play 商店** 統一安全發行（GitHub 不再提供 APK 下載）。請依序透過以下三個連結加入測試並下載安裝：

1. **加入 Google 測試群組**：https://groups.google.com/g/dx-file-manager-testers
2. **申請成為測試人員（網頁加入）**：https://play.google.com/apps/testing/com.dx9901mk5.pixelvoiceime
3. **Google Play App 下載連結**：https://play.google.com/store/apps/details?id=com.dx9901mk5.pixelvoiceime

---

## ✨ 核心特色

1. **切換即自主收音與判定**：切換至 `DxVoice-IME` 後立即自動啟動語音接收與停頓判定（密碼欄位自動防護略過）。
2. **60+ 語系離線語音辨識**：內建 SenseVoice / Whisper 多語離線模型管理、自動標點與繁簡中文精準轉換。
3. **本地極速小模型與自定義詞庫（Pro）**：支援裝置端 `Qwen2.5 0.5B` 離線錯字修潤、專有名詞詞庫、0ms 即時詞彙替換與手動改字自動學習。
4. **懸浮語音捷徑球與自動切回原輸入法（Pro）**：使用其他鍵盤時自動顯示可拖曳懸浮麥克風球（摺疊機分開記憶內外螢幕座標），說完自動貼回並切回先前鍵盤。
5. **四語介面與平板並排雙欄**：內建 **繁體中文／简体中文／English／日本語** 免重啟即時切換，平板與 Chromebook 支援左右雙欄並排模式與單欄切換。
6. **多元雲端與備份支援**：可選配 BYOK Gemini API 或自架區網 Whisper / DeepSeek / Ollama 端點；支援 Google 雲端硬碟／本機資料夾一鍵備份還原（含自定義詞庫）。

## 🙏 開源銘謝與特別感謝

- 開源模型與函式庫：SenseVoice、OpenAI Whisper、CT-Transformer、Qwen2.5-0.5B-Instruct、Sherpa-ONNX、Google MediaPipe Tasks GenAI、AndroidX / Jetpack Compose / Material 3、OkHttp、Apache Commons Compress、Android ICU、Google ML Kit。
- **特別感謝協助開發與參與測試人員**：曉東、Kimi！

## 🛠️ 建置方式

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
.\gradlew.bat assemblePlayRelease bundlePlayRelease --no-daemon
```
