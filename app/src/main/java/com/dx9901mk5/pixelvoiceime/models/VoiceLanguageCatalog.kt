package com.dx9901mk5.pixelvoiceime.models

/**
 * Offline speech model pack metadata (real open-source ONNX ASR model archives).
 */
data class OfflineModelPack(
    val packId: String,
    val title: String,
    val description: String,
    val approxSizeMb: Int,
    val downloadUrl: String,
    val isArchiveTarBz2: Boolean = true
)

/**
 * A language entry mirroring Google Voice Typing's offline language catalog.
 */
data class VoiceLanguageItem(
    val bcp47: String,
    val displayName: String,
    val nativeName: String,
    val regionGroup: String,
    val packId: String
)

object VoiceLanguageCatalog {

    const val PACK_SENSEVOICE_CJK = "sensevoice-zh-en-ja-ko-yue"
    const val PACK_ZIPFORMER_EN = "zipformer-en"
    const val PACK_WHISPER_MULTI = "whisper-tiny-multilingual"
    const val PACK_WHISPER_BASE_MULTI = "whisper-base-multilingual"
    const val PACK_PUNCTUATION_ZH_EN = "punct-ct-transformer-zh-en"
    const val PACK_LOCAL_LLM_QWEN25_05B = "qwen25-0.5b-instruct-q8"
    const val PACK_SPEAKER_3DSPEAKER = "speaker-3dspeaker-campplus"
    const val LEGACY_PACK_GEMMA3_1B = "gemma3-1b-it-int4"

    val modelPacks: Map<String, OfflineModelPack> = linkedMapOf(
        PACK_SENSEVOICE_CJK to OfflineModelPack(
            packId = PACK_SENSEVOICE_CJK,
            title = "SenseVoice 多語離線包 (中/粵/英/日/韓)",
            description = "高精度裝置端量化模型，共用權重涵蓋繁中、簡中、粵語、英文、日文、韓文",
            approxSizeMb = 160,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
        ),
        PACK_PUNCTUATION_ZH_EN to OfflineModelPack(
            packId = PACK_PUNCTUATION_ZH_EN,
            title = "CT-Transformer 本地標點符號模型 (中/英)",
            description = "純離線自動補齊逗號、句號、問號，延遲僅數十毫秒",
            approxSizeMb = 62,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"
        ),
        PACK_LOCAL_LLM_QWEN25_05B to OfflineModelPack(
            packId = PACK_LOCAL_LLM_QWEN25_05B,
            title = "Qwen2.5 0.5B 極速中文修潤模型 (GPU 加速)",
            description = "MediaPipe 裝置端輕量中文 LLM，速度為 Gemma 3 1B 的 2~3 倍且中文更精準（支援 GPU 加速與自動降級 CPU）",
            approxSizeMb = 521,
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            isArchiveTarBz2 = false
        ),
        PACK_SPEAKER_3DSPEAKER to OfflineModelPack(
            packId = PACK_SPEAKER_3DSPEAKER,
            title = "3D-Speaker 個人聲紋辨識模型",
            description = "輕量級離線聲紋向量模型，僅收錄用戶本人聲音並過濾旁人與背景電視聲",
            approxSizeMb = 27,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            isArchiveTarBz2 = false
        ),
        PACK_ZIPFORMER_EN to OfflineModelPack(
            packId = PACK_ZIPFORMER_EN,
            title = "Whisper Tiny 英語專屬離線包",
            description = "極速輕量英語離線辨識模型（美/英/澳/加/印等英語變體）",
            approxSizeMb = 113,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
        ),
        PACK_WHISPER_MULTI to OfflineModelPack(
            packId = PACK_WHISPER_MULTI,
            title = "Whisper Tiny 全球多語離線包",
            description = "輕量級 99 語系全球離線模型（歐洲、東南亞、南亞、中東等語系共用）",
            approxSizeMb = 113,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
        ),
        PACK_WHISPER_BASE_MULTI to OfflineModelPack(
            packId = PACK_WHISPER_BASE_MULTI,
            title = "Whisper Base 進階多語離線包",
            description = "更高精準度的 99 語系全球離線模型",
            approxSizeMb = 199,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
        )
    )

    /**
     * Comprehensive language list modeled after Google Voice Typing (Gboard / Speech Services by Google).
     */
    val languages: List<VoiceLanguageItem> = listOf(
        // 中文與東亞
        VoiceLanguageItem("zh-TW", "繁體中文 (台灣)", "國語 (台灣)", "中文與東亞", PACK_SENSEVOICE_CJK),
        VoiceLanguageItem("yue-Hant-HK", "粵語 (香港)", "廣東話 (香港)", "中文與東亞", PACK_SENSEVOICE_CJK),
        VoiceLanguageItem("zh-HK", "繁體中文 (香港)", "中文 (香港)", "中文與東亞", PACK_SENSEVOICE_CJK),
        VoiceLanguageItem("zh-CN", "簡體中文 (中國)", "普通话 (中国大陆)", "中文與東亞", PACK_SENSEVOICE_CJK),
        VoiceLanguageItem("ja-JP", "日文 (日本)", "日本語", "中文與東亞", PACK_SENSEVOICE_CJK),
        VoiceLanguageItem("ko-KR", "韓文 (韓國)", "한국어", "中文與東亞", PACK_SENSEVOICE_CJK),

        // 英語
        VoiceLanguageItem("en-US", "英文 (美國)", "English (United States)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-GB", "英文 (英國)", "English (United Kingdom)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-AU", "英文 (澳洲)", "English (Australia)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-CA", "英文 (加拿大)", "English (Canada)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-IN", "英文 (印度)", "English (India)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-SG", "英文 (新加坡)", "English (Singapore)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-NZ", "英文 (紐西蘭)", "English (New Zealand)", "英語系", PACK_ZIPFORMER_EN),
        VoiceLanguageItem("en-PH", "英文 (菲律賓)", "English (Philippines)", "英語系", PACK_ZIPFORMER_EN),

        // 東南亞與南亞
        VoiceLanguageItem("vi-VN", "越南文", "Tiếng Việt", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("th-TH", "泰文", "ไทย", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("id-ID", "印尼文", "Bahasa Indonesia", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ms-MY", "馬來文", "Bahasa Melayu", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("fil-PH", "菲律賓文", "Filipino", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("hi-IN", "印地文", "हिन्दी", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("bn-IN", "孟加拉文", "বাংলা", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ta-IN", "坦米爾文", "தமிழ்", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("te-IN", "泰盧固文", "తెలుగు", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("mr-IN", "馬拉地文", "मराठी", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ur-PK", "烏爾都文", "اردو", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("km-KH", "高棉文", "ខ្មែរ", "東南亞與南亞", PACK_WHISPER_MULTI),
        VoiceLanguageItem("my-MM", "緬甸文", "မြန်မာ", "東南亞與南亞", PACK_WHISPER_MULTI),

        // 歐洲
        VoiceLanguageItem("es-ES", "西班牙文 (西班牙)", "Español (España)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("es-MX", "西班牙文 (墨西哥)", "Español (México)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("es-US", "西班牙文 (美國)", "Español (Estados Unidos)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("fr-FR", "法文 (法國)", "Français (France)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("fr-CA", "法文 (加拿大)", "Français (Canada)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("de-DE", "德文 (德國)", "Deutsch (Deutschland)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("de-AT", "德文 (奧地利)", "Deutsch (Österreich)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("de-CH", "德文 (瑞士)", "Deutsch (Schweiz)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("it-IT", "義大利文", "Italiano", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("pt-BR", "葡萄牙文 (巴西)", "Português (Brasil)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("pt-PT", "葡萄牙文 (葡萄牙)", "Português (Portugal)", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ru-RU", "俄文", "Русский", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("uk-UA", "烏克蘭文", "Українська", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("nl-NL", "荷蘭文", "Nederlands", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("pl-PL", "波蘭文", "Polski", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("tr-TR", "土耳其文", "Türkçe", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("sv-SE", "瑞典文", "Svenska", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("nb-NO", "挪威文", "Norsk bokmål", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("da-DK", "丹麥文", "Dansk", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("fi-FI", "芬蘭文", "Suomi", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("cs-CZ", "捷克文", "Čeština", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("hu-HU", "匈牙利文", "Magyar", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ro-RO", "羅馬尼亞文", "Română", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("el-GR", "希臘文", "Ελληνικά", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("sk-SK", "斯洛伐克文", "Slovenčina", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("hr-HR", "克羅埃西亞文", "Hrvatski", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("bg-BG", "保加利亞文", "Български", "歐洲語系", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ca-ES", "加泰隆尼亞文", "Català", "歐洲語系", PACK_WHISPER_MULTI),

        // 中東與非洲
        VoiceLanguageItem("ar-SA", "阿拉伯文 (沙烏地阿拉伯)", "العربية (السعودية)", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ar-EG", "阿拉伯文 (埃及)", "العربية (مصر)", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("ar-AE", "阿拉伯文 (阿聯)", "العربية (الإمارات)", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("he-IL", "希伯來文", "עברית", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("fa-IR", "波斯文", "فارسی", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("sw-KE", "史瓦希里文", "Kiswahili", "中東與非洲", PACK_WHISPER_MULTI),
        VoiceLanguageItem("af-ZA", "南非荷蘭文", "Afrikaans", "中東與非洲", PACK_WHISPER_MULTI)
    )

    fun findByTag(bcp47: String): VoiceLanguageItem {
        return languages.firstOrNull { it.bcp47.equals(bcp47, ignoreCase = true) }
            ?: languages.first()
    }
}
