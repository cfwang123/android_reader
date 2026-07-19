package com.whj.reader.util

import java.util.Locale

/**
 * TTS 语言列表排序：中 → 英 → 日 → 韩 → 亚洲其它 → 欧洲 → 其它。
 */
object VoiceLocaleOrder {

    private val asianOther = setOf(
        "th", "vi", "id", "ms", "hi", "bn", "ta", "te", "ml", "kn", "mr", "gu",
        "pa", "ne", "si", "my", "km", "lo", "bo", "ug", "mn", "fil", "tl", "jv",
        "su", "ceb", "kmr", "ps", "fa", "ur", "sd", "dz", "am", "ti",
    )

    private val european = setOf(
        "de", "fr", "es", "it", "pt", "ru", "nl", "pl", "uk", "cs", "sk", "ro",
        "hu", "sv", "nb", "nn", "no", "da", "fi", "el", "bg", "hr", "sr", "sl",
        "lt", "lv", "et", "is", "ga", "mt", "sq", "mk", "bs", "ca", "eu", "gl",
        "cy", "be", "az", "hy", "ka", "tr", "lb", "rm", "fo", "af", "sw",
    )

    /** 0=中 1=英 2=日 3=韩 4=亚洲其它 5=欧洲 6=其它 */
    fun groupRank(languageTag: String): Int {
        val lang = languageTag.substringBefore('_').substringBefore('-').lowercase(Locale.ROOT)
        return when {
            lang.startsWith("zh") || lang == "yue" || lang == "wuu" -> 0
            lang.startsWith("en") -> 1
            lang.startsWith("ja") -> 2
            lang.startsWith("ko") -> 3
            lang in asianOther -> 4
            lang in european -> 5
            else -> 6
        }
    }

    fun sortLangKeys(keys: Collection<String>): List<String> =
        keys.sortedWith(
            compareBy<String> { groupRank(it) }
                .thenBy { it.lowercase(Locale.ROOT) },
        )
}
