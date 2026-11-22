package com.dx9901mk5.pixelvoiceime.ai

import com.k2fsa.sherpa.onnx.OfflinePunctuation

/**
 * Ultra-fast (< 5ms) on-device text polish:
 * 1. Removes spoken fillers (嗯、啊、呃、那個).
 * 2. Normalizes common Simplified Chinese characters/terms into Traditional Chinese (Taiwan).
 * 3. Runs local CT-Transformer punctuation (if loaded) and enforces clause-comma / ending-period rules.
 */
object LocalTextPolisher {

    private val fillerRegex = Regex("""^(嗯+|啊+|呃+|那個+|就是說+)[，,\s]*""")
    private val midFillerRegex = Regex("""[，,\s]+(嗯+|呃+|那個+)[，,\s]+""")

    private val termReplacements = listOf(
        "软件" to "軟體",
        "硬件" to "硬體",
        "网络" to "網路",
        "视频" to "影片",
        "信息" to "資訊",
        "数据" to "資料",
        "程序" to "程式",
        "服务器" to "伺服器",
        "鼠标" to "滑鼠",
        "屏幕" to "螢幕",
        "手机" to "手機",
        "电脑" to "電腦",
        "语音" to "語音",
        "输入法" to "輸入法",
        "设置" to "設定",
        "默认" to "預設",
        "链接" to "連結",
        "下载" to "下載",
        "上传" to "上傳",
        "登录" to "登入",
        "账号" to "帳號",
        "密码" to "密碼",
        "文件" to "檔案",
        "文件夹" to "資料夾",
        "支持" to "支援",
        "优化" to "最佳化",
        "测试" to "測試",
        "问题" to "問題",
        "这个" to "這個",
        "那个" to "那個",
        "什么" to "什麼",
        "怎么" to "怎麼",
        "为什么" to "為什麼",
        "哪里" to "哪裡",
        "这里" to "這裡",
        "那里" to "那裡",
        "现在" to "現在",
        "时候" to "時候",
        "可以" to "可以",
        "没有" to "沒有",
        "知道" to "知道",
        "觉得" to "覺得",
        "应该" to "應該",
        "因为" to "因為",
        "所以" to "所以",
        "如果" to "如果",
        "虽然" to "雖然",
        "但是" to "但是",
        "而且" to "而且",
        "然后" to "然後",
        "还是" to "還是",
        "或者" to "或者",
        "已经" to "已經",
        "需要" to "需要",
        "东西" to "東西",
        "事情" to "事情",
        "朋友" to "朋友",
        "谢谢" to "謝謝",
        "对不起" to "對不起",
        "没关系" to "沒關係",
        "高兴" to "高興",
        "喜欢" to "喜歡",
        "非常" to "非常",
        "特别" to "特別",
        "大家" to "大家",
        "我们" to "我們",
        "你们" to "你們",
        "他们" to "他們",
        "她们" to "她們"
    )

    private val charMap: Map<Char, Char> = mapOf(
        '这' to '這', '个' to '個', '们' to '們', '说' to '說', '话' to '話',
        '会' to '會', '对' to '對', '吗' to '嗎', '吧' to '吧', '呢' to '呢',
        '听' to '聽', '见' to '見', '看' to '看', '写' to '寫', '读' to '讀',
        '买' to '買', '卖' to '賣', '开' to '開', '关' to '關', '门' to '門',
        '问' to '問', '题' to '題', '时' to '時', '间' to '間', '点' to '點',
        '钟' to '鐘', '分' to '分', '秒' to '秒', '年' to '年', '天' to '天',
        '号' to '號', '车' to '車', '飞' to '飛', '机' to '機', '电' to '電',
        '脑' to '腦', '网' to '網', '络' to '絡', '线' to '線', '连' to '連',
        '接' to '接', '发' to '發', '现' to '現', '实' to '實', '际' to '際',
        '经' to '經', '过' to '過', '进' to '進', '出' to '出', '来' to '來',
        '去' to '去', '回' to '回', '到' to '到', '给' to '給', '让' to '讓',
        '把' to '把', '被' to '被', '从' to '從', '向' to '向', '往' to '往',
        '与' to '與', '和' to '和', '或' to '或', '但' to '但', '却' to '卻',
        '虽' to '雖', '然' to '然', '因' to '因', '为' to '為', '所' to '所',
        '以' to '以', '如' to '如', '果' to '果', '就' to '就', '才' to '才',
        '都' to '都', '也' to '也', '还' to '還', '又' to '又', '再' to '再',
        '更' to '更', '最' to '最', '很' to '很', '太' to '太', '真' to '真',
        '好' to '好', '坏' to '壞', '新' to '新', '旧' to '舊', '快' to '快',
        '慢' to '慢', '早' to '早', '晚' to '晚', '长' to '長', '短' to '短',
        '高' to '高', '低' to '低', '大' to '大', '小' to '小', '多' to '多',
        '少' to '少', '难' to '難', '易' to '易', '对' to '對', '错' to '錯',
        '请' to '請', '谢' to '謝', '爱' to '愛', '想' to '想', '觉' to '覺',
        '得' to '得', '知' to '知', '道' to '道', '懂' to '懂', '学' to '學',
        '习' to '習', '做' to '做', '作' to '作', '工' to '工', '厂' to '廠',
        '东' to '東', '西' to '西', '南' to '南', '北' to '北', '边' to '邊',
        '里' to '裡', '面' to '面', '外' to '外', '内' to '內', '前' to '前',
        '后' to '後', '左' to '左', '右' to '右', '中' to '中', '间' to '間',
        '国' to '國', '语' to '語', '文' to '文', '字' to '字', '词' to '詞',
        '句' to '句', '标' to '標', '点' to '點', '符' to '符', '号' to '號',
        '测' to '測', '试' to '試', '验' to '驗', '证' to '證', '明' to '明',
        '准' to '準', '确' to '確', '认' to '認', '识' to '識', '别' to '別',
        '选' to '選', '择' to '擇', '项' to '項', '目' to '目', '录' to '錄',
        '音' to '音', '声' to '聲', '响' to '響', '乐' to '樂', '歌' to '歌',
        '图' to '圖', '片' to '片', '画' to '畫', '视' to '視', '频' to '頻',
        '页' to '頁', '书' to '書', '写' to '寫', '记' to '記', '录' to '錄',
        '传' to '傳', '输' to '輸', '入' to '入', '法' to '法', '统' to '統',
        '系' to '系', '设' to '設', '置' to '置', '更' to '更', '换' to '換',
        '改' to '改', '变' to '變', '动' to '動', '启' to '啟', '停' to '停',
        '止' to '止', '结' to '結', '束' to '束', '完' to '完', '成' to '成',
        '功' to '功', '失' to '失', '败' to '敗', '错' to '錯', '误' to '誤',
        '帮' to '幫', '助' to '助', '处' to '處', '理' to '理', '解' to '解',
        '决' to '決', '定' to '定', '计' to '計', '划' to '劃', '算' to '算',
        '数' to '數', '据' to '據', '资' to '資', '料' to '料', '讯' to '訊',
        '息' to '息', '体' to '體', '验' to '驗', '软' to '軟', '硬' to '硬',
        '件' to '件', '档' to '檔', '案' to '案', '夹' to '夾', '储' to '儲',
        '存' to '存', '删' to '刪', '除' to '除', '移' to '移', '复' to '複',
        '制' to '製', '贴' to '貼', '剪' to '剪', '切' to '切', '搜' to '搜',
        '寻' to '尋', '找' to '找', '查' to '查', '看' to '看', '显' to '顯',
        '示' to '示', '隐' to '隱', '藏' to '藏', '轻' to '輕', '重' to '重',
        '块' to '塊', '钱' to '錢', '元' to '元', '万' to '萬', '亿' to '億',
        '两' to '兩', '几' to '幾', '双' to '雙', '单' to '單', '总' to '總',
        '全' to '全', '部' to '部', '分' to '分', '种' to '種', '类' to '類',
        '样' to '樣', '张' to '張', '条' to '條', '只' to '隻', '台' to '台',
        '辆' to '輛', '家' to '家', '店' to '店', '馆' to '館', '院' to '院',
        '校' to '校', '医' to '醫', '药' to '藥', '饭' to '飯', '菜' to '菜',
        '茶' to '茶', '酒' to '酒', '水' to '水', '果' to '果', '肉' to '肉',
        '鱼' to '魚', '鸡' to '雞', '蛋' to '蛋', '面' to '麵', '汤' to '湯',
        '热' to '熱', '冷' to '冷', '凉' to '涼', '暖' to '暖', '干' to '乾',
        '净' to '淨', '脏' to '髒', '乱' to '亂', '整' to '整', '齐' to '齊',
        '满' to '滿', '空' to '空', '轻' to '輕', '松' to '鬆', '紧' to '緊',
        '忙' to '忙', '闲' to '閒', '累' to '累', '饿' to '餓', '渴' to '渴',
        '睡' to '睡', '醒' to '醒', '起' to '起', '床' to '床', '坐' to '坐',
        '站' to '站', '走' to '走', '跑' to '跑', '跳' to '跳', '飞' to '飛',
        '游' to '遊', '玩' to '玩', '笑' to '笑', '哭' to '哭', '喊' to '喊',
        '叫' to '叫', '唱' to '唱', '跳' to '跳', '画' to '畫', '写' to '寫',
        '给' to '給', '拿' to '拿', '带' to '帶', '送' to '送', '收' to '收',
        '寄' to '寄', '邮' to '郵', '件' to '件', '包' to '包', '裹' to '裹',
        '装' to '裝', '卸' to '卸', '载' to '載', '运' to '運', '行' to '行',
        '欢' to '歡', '迎' to '迎', '抱' to '抱', '歉' to '歉', '麻' to '麻',
        '烦' to '煩', '劳' to '勞', '驾' to '駕', '拜' to '拜', '托' to '託',
        '希' to '希', '望' to '望', '祝' to '祝', '愿' to '願', '意' to '意',
        '思' to '思', '考' to '考', '虑' to '慮', '决' to '決', '策' to '策',
        '选' to '選', '举' to '舉', '办' to '辦', '公' to '公', '司' to '司',
        '厂' to '廠', '商' to '商', '业' to '業', '务' to '務', '客' to '客',
        '户' to '戶', '服' to '服', '产' to '產', '品' to '品', '质' to '質',
        '价' to '價', '格' to '格', '便' to '便', '宜' to '宜', '贵' to '貴'
    )

    fun polish(
        rawText: String,
        languageTag: String,
        autoPunctuation: Boolean,
        removeFillers: Boolean,
        punctuationModel: OfflinePunctuation?,
        polisherHelper: GeminiPolisher
    ): String {
        var text = rawText.trim()
        if (text.isBlank()) return ""

        if (removeFillers) {
            text = text.replace(fillerRegex, "").replace(midFillerRegex, "，").trim()
        }

        if (languageTag.startsWith("zh-TW", ignoreCase = true) ||
            languageTag.startsWith("zh-HK", ignoreCase = true) ||
            languageTag.startsWith("yue", ignoreCase = true)
        ) {
            text = toTraditionalTaiwan(text)
        }

        if (autoPunctuation) {
            if (punctuationModel != null &&
                (languageTag.startsWith("zh", ignoreCase = true) ||
                    languageTag.startsWith("en", ignoreCase = true) ||
                    languageTag.startsWith("yue", ignoreCase = true))
            ) {
                runCatching {
                    val punctuated = punctuationModel.addPunctuation(text).trim()
                    if (punctuated.isNotBlank()) {
                        text = punctuated
                    }
                }
            }
            text = polisherHelper.localFallbackPunctuate(text, languageTag)
        }

        return text.trim()
    }

    fun toTraditionalTaiwan(input: String): String {
        var result = input
        for ((src, dst) in termReplacements) {
            if (result.contains(src)) {
                result = result.replace(src, dst)
            }
        }
        val sb = StringBuilder(result.length)
        for (ch in result) {
            sb.append(charMap[ch] ?: ch)
        }
        return sb.toString()
    }
}
