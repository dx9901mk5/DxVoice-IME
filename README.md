# DxVoice-IME | 離線 AI 智慧語音輸入法 · Offline AI Voice Keyboard

[繁體中文](#繁體中文) | [English](#english)

---

<a id="繁體中文"></a>
## 🇹🇼 繁體中文

> **專為 Android 手機、平板、摺疊機與 Chromebook 打造的極速離線 AI 語音輸入法。主打「說完自動切回原輸入法」與「跟隨鍵盤的懸浮語音按鈕」，結合 60+ 語系離線辨識、自動標點、Qwen2.5 0.5B 本地修潤與自定義詞庫。**

### 📲 下載與加入 Google Play 封閉測試

本專案已改為透過 **Google Play 商店** 統一安全發行（GitHub 不再提供 APK 檔案）。請依序透過以下三個連結加入測試並下載安裝：

1. **加入 Google 測試群組**：https://groups.google.com/g/dx-file-manager-testers
2. **申請成為測試人員（網頁加入）**：https://play.google.com/apps/testing/com.dx9901mk5.pixelvoiceime
3. **Google Play App 下載連結**：https://play.google.com/store/apps/details?id=com.dx9901mk5.pixelvoiceime

### ✨ 核心特色

- **🔄 自動切回原本輸入法（Auto-Return）**：切換至 `DxVoice-IME` 立刻開始語音收音；說完自動修潤標點上屏後，**自動切回原本使用的鍵盤**（支援自訂延遲秒數或手動一鍵切回），讓語音與打字無縫銜接。
- **🟣 懸浮語音捷徑按鈕（Floating Mic Button）**：使用其他輸入法時自動浮現精巧（`28dp`）可拖曳的懸浮麥克風球，點擊免跳選單直接啟動語音收音；摺疊機自動分開記憶外螢幕與內螢幕位置，並可調整透明度。
- **🎙️ 60+ 語系離線語音辨識**：內建 SenseVoice / Whisper 多語離線模型管理、自動標點符號與繁簡中文精準轉換，無網路也能極速輸入。
- **🧠 本地極速小模型與自定義詞庫**：支援裝置端 `Qwen2.5 0.5B` 離線口語錯字修潤、專有名詞詞庫、0ms 即時詞彙替換與手動改字自動學習。
- **🌐 四語介面與平板並排雙欄**：內建 **繁體中文／简体中文／English／日本語** 免重啟即時切換；平板與 Chromebook 支援左右雙欄並排模式與單欄切換。
- **☁️ 多元雲端端點與一鍵備份還原**：可選配自帶 Gemini API Key 或自架區網 Whisper / DeepSeek / Ollama 端點；支援 Google 雲端硬碟與本機資料夾一鍵備份還原所有設定與自定義詞庫。

### 🙏 開源銘謝與特別感謝

- 開源模型與函式庫：SenseVoice、OpenAI Whisper、CT-Transformer、Qwen2.5-0.5B-Instruct、Sherpa-ONNX、Google MediaPipe Tasks GenAI、AndroidX / Jetpack Compose / Material 3、OkHttp、Apache Commons Compress、Android ICU、Google ML Kit。
- **特別感謝協助開發與參與測試人員**：曉東、Kimi！
- **隱私權政策**：[PRIVACY_POLICY.md](PRIVACY_POLICY.md)

---

<a id="english"></a>
## 🇺🇸 English

> **A fast, on-device AI voice keyboard built for Android phones, tablets, foldables, and Chromebooks. Featuring automatic return to your previous keyboard, a keyboard-aware floating voice shortcut button, 60+ offline speech recognition languages, local Qwen2.5 0.5B polishing, and custom dictionary learning.**

### 📲 Download & Join Google Play Closed Testing

This app is distributed exclusively through **Google Play** (no APK files are hosted on GitHub). Join the closed beta in 3 steps:

1. **Join the Google Testing Group**: https://groups.google.com/g/dx-file-manager-testers
2. **Opt-in as a Tester (Web)**: https://play.google.com/apps/testing/com.dx9901mk5.pixelvoiceime
3. **Download on Google Play**: https://play.google.com/store/apps/details?id=com.dx9901mk5.pixelvoiceime

### ✨ Key Features

- **🔄 Auto-Return to Previous Keyboard**: Immediately starts listening when switched in, polishes punctuation and typos, commits text, and **automatically switches right back to your previous keyboard** (configurable delay or manual return).
- **🟣 Floating Voice Shortcut Button**: Displays a compact (`28dp`), draggable floating mic button automatically whenever another keyboard is open—tap once to jump straight into voice typing without opening the IME picker. Remembers outer & inner screen positions separately on foldables.
- **🎙️ 60+ Offline Speech Recognition Languages**: Powered by on-device SenseVoice / Whisper models with offline auto-punctuation and Traditional/Simplified Chinese conversion.
- **🧠 On-Device Fast LLM & Custom Dictionary**: Offline `Qwen2.5 0.5B` text refinement, custom proper-noun vocabulary, 0ms instant text replacements, and automatic habit learning from manual edits.
- **🌐 4-Language UI & Adaptive Tablet Split-View**: Instant zero-restart UI switching (**繁體中文 / 简体中文 / English / 日本語**) plus side-by-side two-pane layout for tablets and Chromebooks.
- **☁️ Flexible Cloud Endpoints & One-Tap Backup**: Optional BYOK Gemini API or self-hosted LAN Whisper / DeepSeek / Ollama endpoints; one-tap backup & restore of all settings and custom dictionaries to Google Drive or local folders.

### 🙏 Open-Source Credits & Special Thanks

- Built with SenseVoice, OpenAI Whisper, CT-Transformer, Qwen2.5-0.5B-Instruct, Sherpa-ONNX, Google MediaPipe Tasks GenAI, AndroidX / Jetpack Compose / Material 3, OkHttp, Apache Commons Compress, Android ICU, and Google ML Kit.
- **Special Thanks** to co-development & testing contributors: **XiaoDong (曉東)** and **Kimi**!
- **Privacy Policy**: [PRIVACY_POLICY.md](PRIVACY_POLICY.md)

---

## 🛠️ Build from Source

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
.\gradlew.bat assemblePlayRelease bundlePlayRelease --no-daemon
```
