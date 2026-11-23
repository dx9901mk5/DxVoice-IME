package com.dx9901mk5.pixelvoiceime.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ReplacementRule(
    val from: String,
    val to: String
)

data class LearnedPhrase(
    val phrase: String,
    val sourceWord: String = "",
    val count: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis()
)

data class CustomDictionaryState(
    val customWords: List<String> = emptyList(),
    val replacements: List<ReplacementRule> = emptyList(),
    val learnedPhrases: List<LearnedPhrase> = emptyList(),
    val blockedPhrases: List<String> = emptyList(),
    val autoLearnEnabled: Boolean = true,
    val useAsHintsEnabled: Boolean = true
)

/**
 * Manages user-defined proper nouns, 0ms local replacement rules, and on-device
 * habitual phrase learning (correction-diff learning + frequency mining).
 */
class CustomDictionaryStore(context: Context) {

    private val prefs = context.getSharedPreferences("pixel_voice_dictionary", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<CustomDictionaryState> = _state.asStateFlow()

    private fun loadState(): CustomDictionaryState {
        val raw = prefs.getString(KEY_DICTIONARY_JSON, null) ?: return CustomDictionaryState()
        return try {
            parseJson(JSONObject(raw))
        } catch (_: Exception) {
            CustomDictionaryState()
        }
    }

    private fun parseJson(root: JSONObject): CustomDictionaryState {
        val words = mutableListOf<String>()
        val wordsArr = root.optJSONArray("customWords")
        if (wordsArr != null) {
            for (i in 0 until wordsArr.length()) {
                val w = wordsArr.optString(i).trim()
                if (w.isNotBlank() && !words.contains(w)) words.add(w)
            }
        }

        val replacements = mutableListOf<ReplacementRule>()
        val repArr = root.optJSONArray("replacements")
        if (repArr != null) {
            for (i in 0 until repArr.length()) {
                val obj = repArr.optJSONObject(i) ?: continue
                val from = obj.optString("from").trim()
                val to = obj.optString("to").trim()
                if (from.isNotBlank() && to.isNotBlank() && from != to) {
                    replacements.add(ReplacementRule(from, to))
                }
            }
        }

        val learned = mutableListOf<LearnedPhrase>()
        val learnedArr = root.optJSONArray("learnedPhrases")
        if (learnedArr != null) {
            for (i in 0 until learnedArr.length()) {
                val obj = learnedArr.optJSONObject(i) ?: continue
                val phrase = obj.optString("phrase").trim()
                val source = obj.optString("sourceWord").trim()
                val count = obj.optInt("count", 1).coerceAtLeast(1)
                val ts = obj.optLong("lastUsedAt", System.currentTimeMillis())
                if (phrase.isNotBlank()) {
                    learned.add(LearnedPhrase(phrase = phrase, sourceWord = source, count = count, lastUsedAt = ts))
                }
            }
        }

        val blocked = mutableListOf<String>()
        val blockedArr = root.optJSONArray("blockedPhrases")
        if (blockedArr != null) {
            for (i in 0 until blockedArr.length()) {
                val b = blockedArr.optString(i).trim()
                if (b.isNotBlank() && !blocked.contains(b)) blocked.add(b)
            }
        }

        return CustomDictionaryState(
            customWords = words,
            replacements = replacements,
            learnedPhrases = learned.sortedByDescending { it.count * 10_000L + (it.lastUsedAt / 100_000L) },
            blockedPhrases = blocked,
            autoLearnEnabled = root.optBoolean("autoLearnEnabled", true),
            useAsHintsEnabled = root.optBoolean("useAsHintsEnabled", true)
        )
    }

    private fun saveState(updated: CustomDictionaryState) {
        _state.value = updated
        prefs.edit().putString(KEY_DICTIONARY_JSON, toJson(updated).toString()).apply()
    }

    fun exportJson(): JSONObject = toJson(_state.value)

    fun importJson(json: JSONObject, merge: Boolean = true) {
        val incoming = parseJson(json)
        if (!merge) {
            saveState(incoming)
            return
        }
        val cur = _state.value
        val mergedWords = (cur.customWords + incoming.customWords).distinct()
        val mergedReps = (cur.replacements + incoming.replacements).distinctBy { it.from }
        val mergedBlocked = (cur.blockedPhrases + incoming.blockedPhrases).distinct()
        val learnedMap = LinkedHashMap<Pair<String, String>, LearnedPhrase>()
        (cur.learnedPhrases + incoming.learnedPhrases).forEach { item ->
            if (!mergedBlocked.contains(item.phrase)) {
                val key = item.phrase to item.sourceWord
                val existing = learnedMap[key]
                if (existing == null || item.count > existing.count) {
                    learnedMap[key] = item
                }
            }
        }
        saveState(
            cur.copy(
                customWords = mergedWords,
                replacements = mergedReps,
                learnedPhrases = learnedMap.values.sortedByDescending { it.count }.take(MAX_LEARNED_ENTRIES),
                blockedPhrases = mergedBlocked,
                autoLearnEnabled = incoming.autoLearnEnabled,
                useAsHintsEnabled = incoming.useAsHintsEnabled
            )
        )
    }

    private fun toJson(s: CustomDictionaryState): JSONObject {
        return JSONObject().apply {
            put("autoLearnEnabled", s.autoLearnEnabled)
            put("useAsHintsEnabled", s.useAsHintsEnabled)
            put("customWords", JSONArray(s.customWords))
            put(
                "replacements",
                JSONArray().apply {
                    s.replacements.forEach {
                        put(JSONObject().put("from", it.from).put("to", it.to))
                    }
                }
            )
            put(
                "learnedPhrases",
                JSONArray().apply {
                    s.learnedPhrases.forEach {
                        put(
                            JSONObject()
                                .put("phrase", it.phrase)
                                .put("sourceWord", it.sourceWord)
                                .put("count", it.count)
                                .put("lastUsedAt", it.lastUsedAt)
                        )
                    }
                }
            )
            put("blockedPhrases", JSONArray(s.blockedPhrases))
        }
    }

    fun setAutoLearnEnabled(enabled: Boolean) {
        saveState(_state.value.copy(autoLearnEnabled = enabled))
    }

    fun setUseAsHintsEnabled(enabled: Boolean) {
        saveState(_state.value.copy(useAsHintsEnabled = enabled))
    }

    fun addCustomWordsBulk(raw: String) {
        val tokens = raw.split(',', '，', '、', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return
        val cur = _state.value
        val updatedWords = (cur.customWords + tokens).distinct()
        saveState(cur.copy(customWords = updatedWords))
    }

    fun removeCustomWord(word: String) {
        val cur = _state.value
        saveState(cur.copy(customWords = cur.customWords.filterNot { it == word }))
    }

    fun addReplacement(from: String, to: String) {
        val f = from.trim()
        val t = to.trim()
        if (f.isBlank() || t.isBlank() || f == t) return
        val cur = _state.value
        val filtered = cur.replacements.filterNot { it.from == f }
        saveState(cur.copy(replacements = listOf(ReplacementRule(f, t)) + filtered))
    }

    fun removeReplacement(from: String) {
        val cur = _state.value
        saveState(cur.copy(replacements = cur.replacements.filterNot { it.from == from }))
    }

    fun promoteLearnedToCustomWord(phrase: String) {
        val p = phrase.trim()
        if (p.isBlank()) return
        val cur = _state.value
        saveState(
            cur.copy(
                customWords = (listOf(p) + cur.customWords).distinct(),
                learnedPhrases = cur.learnedPhrases.filterNot { it.phrase == p }
            )
        )
    }

    fun promoteLearnedToReplacement(item: LearnedPhrase) {
        if (item.sourceWord.isBlank() || item.phrase.isBlank()) return
        val cur = _state.value
        val filteredReps = cur.replacements.filterNot { it.from == item.sourceWord }
        saveState(
            cur.copy(
                replacements = listOf(ReplacementRule(item.sourceWord, item.phrase)) + filteredReps,
                learnedPhrases = cur.learnedPhrases.filterNot { it.phrase == item.phrase && it.sourceWord == item.sourceWord }
            )
        )
    }

    fun removeAndBlockLearned(phrase: String) {
        val cur = _state.value
        saveState(
            cur.copy(
                learnedPhrases = cur.learnedPhrases.filterNot { it.phrase == phrase },
                blockedPhrases = (cur.blockedPhrases + phrase).distinct().takeLast(200)
            )
        )
    }

    fun clearLearnedPhrases() {
        saveState(_state.value.copy(learnedPhrases = emptyList()))
    }

    /**
     * Applies 0ms local replacements before committing text:
     * 1. Manual user replacement rules (always applied, longest-match first)
     * 2. High-confidence auto-learned correction pairs (count >= 3)
     */
    fun applyReplacements(text: String): String {
        if (text.isBlank()) return text
        val cur = _state.value
        var out = text

        val sortedManual = cur.replacements.sortedByDescending { it.from.length }
        for (rule in sortedManual) {
            if (out.contains(rule.from)) {
                out = out.replace(rule.from, rule.to)
            }
        }

        if (cur.autoLearnEnabled) {
            val highConfidenceLearned = cur.learnedPhrases
                .filter { it.sourceWord.isNotBlank() && it.count >= 3 && !cur.blockedPhrases.contains(it.phrase) }
                .sortedByDescending { it.sourceWord.length }
            for (item in highConfidenceLearned) {
                if (out.contains(item.sourceWord)) {
                    out = out.replace(item.sourceWord, item.phrase)
                }
            }
        }
        return out
    }

    /**
     * Builds concise recognition hints for Whisper's `prompt` parameter (max ~550 chars).
     */
    fun buildWhisperPrompt(maxChars: Int = 550): String {
        val cur = _state.value
        if (!cur.useAsHintsEnabled) return ""
        val activeLearned = if (cur.autoLearnEnabled) {
            cur.learnedPhrases
                .filter { it.count >= 2 && !cur.blockedPhrases.contains(it.phrase) }
                .map { it.phrase }
        } else emptyList()
        val allTerms = (cur.customWords + activeLearned).distinct()
        if (allTerms.isEmpty()) return ""

        val sb = StringBuilder()
        for (term in allTerms) {
            val sep = if (sb.isEmpty()) "" else "、"
            if (sb.length + sep.length + term.length > maxChars) break
            sb.append(sep).append(term)
        }
        return sb.toString()
    }

    /**
     * Builds strict spelling & habitual phrasing reference data for LLM polishers.
     */
    fun buildLlmReferenceHints(maxChars: Int = 1200): String {
        val cur = _state.value
        if (!cur.useAsHintsEnabled) return ""
        val words = cur.customWords
        val learnedPairs = if (cur.autoLearnEnabled) {
            cur.learnedPhrases.filter { it.count >= 2 && !cur.blockedPhrases.contains(it.phrase) }
        } else emptyList()

        if (words.isEmpty() && learnedPairs.isEmpty()) return ""

        return buildString {
            if (words.isNotEmpty()) {
                append("常用專有名詞與詞彙：")
                append(words.joinToString("、").take(600))
            }
            if (learnedPairs.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("使用者慣用語參考：")
                val formatted = learnedPairs.take(25).joinToString("、") { item ->
                    if (item.sourceWord.isNotBlank()) "${item.phrase}(常誤為${item.sourceWord})" else item.phrase
                }
                append(formatted.take((maxChars - length).coerceAtLeast(100)))
            }
        }.take(maxChars)
    }

    /**
     * Track 1 (Correction Diff Learning): compares the committed voice output against
     * the user's manual edit shortly after committing, learning word/phrase corrections.
     */
    fun learnFromUserCorrection(committedText: String, editedSlice: String) {
        val cur = _state.value
        if (!cur.autoLearnEnabled) return
        val orig = committedText.trim()
        val edited = editedSlice.trim()
        if (orig.isBlank() || edited.isBlank() || orig == edited) return
        if (kotlin.math.abs(orig.length - edited.length) > 16) return

        var prefixLen = 0
        val minLen = minOf(orig.length, edited.length)
        while (prefixLen < minLen && orig[prefixLen] == edited[prefixLen]) {
            prefixLen++
        }

        var suffixLen = 0
        while (suffixLen < (minLen - prefixLen) &&
            orig[orig.length - 1 - suffixLen] == edited[edited.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val fromSeg = orig.substring(prefixLen, orig.length - suffixLen).trim()
        val toSeg = edited.substring(prefixLen, edited.length - suffixLen).trim()
        if (fromSeg.length !in 1..8 || toSeg.length !in 2..12) return
        if (!toSeg.any { it.isLetterOrDigit() }) return
        if (cur.blockedPhrases.contains(toSeg) || cur.customWords.contains(toSeg)) return

        upsertLearnedPhrase(phrase = toSeg, sourceWord = fromSeg)
    }

    /**
     * Track 2 (Local Frequency Mining): records repeated alphanumeric/brand terms
     * from committed utterances so frequent jargon is surfaced automatically.
     */
    fun recordCommittedText(text: String) {
        val cur = _state.value
        if (!cur.autoLearnEnabled || text.isBlank()) return
        val terms = ENGLISH_TERM_REGEX.findAll(text)
            .map { it.value.trim('.', ',', '-', '_') }
            .filter { it.length in 2..24 }
            .filter { !cur.blockedPhrases.contains(it) && !cur.customWords.contains(it) }
            .distinct()
            .toList()
        for (term in terms) {
            upsertLearnedPhrase(phrase = term, sourceWord = "")
        }
    }

    private fun upsertLearnedPhrase(phrase: String, sourceWord: String) {
        val cur = _state.value
        val now = System.currentTimeMillis()
        val mutable = cur.learnedPhrases.toMutableList()
        val idx = mutable.indexOfFirst { it.phrase == phrase && it.sourceWord == sourceWord }
        if (idx >= 0) {
            val old = mutable[idx]
            mutable[idx] = old.copy(count = old.count + 1, lastUsedAt = now)
        } else {
            mutable.add(LearnedPhrase(phrase = phrase, sourceWord = sourceWord, count = 1, lastUsedAt = now))
        }
        val trimmed = mutable
            .sortedByDescending { it.count * 10_000L + (it.lastUsedAt / 100_000L) }
            .take(MAX_LEARNED_ENTRIES)
        saveState(cur.copy(learnedPhrases = trimmed))
    }

    companion object {
        private const val KEY_DICTIONARY_JSON = "custom_dictionary_v1"
        private const val MAX_LEARNED_ENTRIES = 160
        private val ENGLISH_TERM_REGEX = Regex("""[A-Za-z][A-Za-z0-9._-]{1,23}""")
    }
}
