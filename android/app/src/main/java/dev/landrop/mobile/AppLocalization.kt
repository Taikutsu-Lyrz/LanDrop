package dev.landrop.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import org.json.JSONObject

internal enum class AppLanguage(val nativeLabel: String, val localeTag: String, val rtl: Boolean = false) {
    English("English", "en"),
    Dari("دری", "prs-AF", true),
    Japanese("日本語", "ja"),
    Spanish("Español", "es"),
    Turkish("Türkçe", "tr"),
    Portuguese("Português", "pt")
}

internal val LocalAppLanguage = compositionLocalOf { AppLanguage.English }
internal val LocalAppTranslations = compositionLocalOf { AppTranslations.Empty }

internal class AppTranslations private constructor(
    private val exact: Map<String, String>,
    private val templates: List<TemplateTranslation>
) {
    fun translate(text: String): String = translate(text, depth = 0)

    private fun translate(text: String, depth: Int): String {
        exact[text]?.let { return it }
        if (depth >= 3) return text
        templates.forEach { template ->
            template.translate(text) { token, value ->
                // Translate app-generated labels inside messages, while leaving
                // names, addresses, paths and other interpolated data alone.
                if (token in localizedMessageTokens && value != text) translate(value, depth + 1)
                else value
            }?.let { return it }
        }
        return text
    }

    companion object {
        private val localizedMessageTokens = setOf(
            "\${connectFailure(reason)}", "\${discoveryFailure(reason)}",
            "\${peer.statusLabel}", "\${peer.linkLabel}", "\$link",
            "\${NetworkDetails.localIpv4Summary()}",
            "\${result.message}", "\$message", "\${error.message}", "\$detail"
        )
        val Empty = AppTranslations(emptyMap(), emptyList())

        fun fromJson(root: JSONObject): AppTranslations {
            val merged = linkedMapOf<String, String>()
            listOf("shared", "android").forEach { sectionName ->
                val section = root.optJSONObject(sectionName) ?: return@forEach
                val keys = section.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    merged[key] = section.optString(key, key)
                }
            }

            val exact = linkedMapOf<String, String>()
            val templates = mutableListOf<TemplateTranslation>()
            merged.forEach { (source, target) ->
                if (source.contains('$')) {
                    buildTemplateTranslation(source, target)?.let(templates::add)
                } else {
                    exact[source] = target
                }
            }
            return AppTranslations(exact, templates.sortedByDescending { it.literalLength })
        }
    }
}

internal fun loadAppTranslations(context: Context, language: AppLanguage): AppTranslations {
    val resource = when (language) {
        AppLanguage.English -> return AppTranslations.Empty
        AppLanguage.Dari -> R.raw.prs_af
        AppLanguage.Japanese -> R.raw.ja
        AppLanguage.Spanish -> R.raw.es
        AppLanguage.Turkish -> R.raw.tr
        AppLanguage.Portuguese -> R.raw.pt
    }
    val raw = context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
    return AppTranslations.fromJson(JSONObject(raw))
}

@Composable
internal fun localizeAppText(text: String): String {
    return if (LocalAppLanguage.current == AppLanguage.English) text
    else LocalAppTranslations.current.translate(text)
}

private data class TemplatePart(
    val literal: String? = null,
    val token: String? = null
)

private data class TemplateTranslation(
    val regex: Regex,
    val targetParts: List<TemplatePart>,
    val tokenGroups: Map<String, Int>,
    val literalLength: Int
) {
    fun translate(text: String, localizeValue: (String, String) -> String): String? {
        val match = regex.matchEntire(text) ?: return null
        return buildString {
            targetParts.forEach { part ->
                when {
                    part.literal != null -> append(part.literal)
                    part.token != null -> {
                        val group = tokenGroups[part.token]
                        if (group != null && group < match.groupValues.size) {
                            append(localizeValue(part.token, match.groupValues[group]))
                        } else {
                            append(part.token)
                        }
                    }
                }
            }
        }
    }
}

private fun buildTemplateTranslation(source: String, target: String): TemplateTranslation? {
    val sourceParts = parseTemplate(source)
    if (sourceParts.none { it.token != null }) return null
    val literalLength = sourceParts.sumOf { it.literal?.length ?: 0 }
    if (literalLength == 0) return null

    val tokenGroups = linkedMapOf<String, Int>()
    var nextGroup = 1
    val pattern = buildString {
        append("^")
        sourceParts.forEach { part ->
            when {
                part.literal != null -> append(Regex.escape(part.literal))
                part.token != null -> {
                    if (!tokenGroups.containsKey(part.token)) {
                        tokenGroups[part.token] = nextGroup
                    }
                    append("(.*?)")
                    nextGroup++
                }
            }
        }
        append("$")
    }

    return runCatching {
        TemplateTranslation(
            regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL),
            targetParts = parseTemplate(target),
            tokenGroups = tokenGroups,
            literalLength = literalLength
        )
    }.getOrNull()
}

private fun parseTemplate(template: String): List<TemplatePart> {
    val parts = mutableListOf<TemplatePart>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            parts += TemplatePart(literal = literal.toString())
            literal.clear()
        }
    }

    var index = 0
    while (index < template.length) {
        if (template[index] == '$' && index + 1 < template.length) {
            val next = template[index + 1]
            if (next == '{') {
                var depth = 1
                var cursor = index + 2
                var inString: Char? = null
                var escaped = false
                while (cursor < template.length && depth > 0) {
                    val char = template[cursor]
                    if (inString != null) {
                        if (escaped) {
                            escaped = false
                        } else if (char == '\\') {
                            escaped = true
                        } else if (char == inString) {
                            inString = null
                        }
                    } else {
                        when (char) {
                            '\'', '"' -> inString = char
                            '{' -> depth++
                            '}' -> depth--
                        }
                    }
                    cursor++
                }
                if (depth == 0) {
                    flushLiteral()
                    parts += TemplatePart(token = template.substring(index, cursor))
                    index = cursor
                    continue
                }
            } else if (next == '_' || next.isLetter()) {
                var cursor = index + 2
                while (cursor < template.length &&
                    (template[cursor] == '_' || template[cursor].isLetterOrDigit())
                ) {
                    cursor++
                }
                flushLiteral()
                parts += TemplatePart(token = template.substring(index, cursor))
                index = cursor
                continue
            }
        }

        literal.append(template[index])
        index++
    }

    flushLiteral()
    return parts
}
