package dev.landrop.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject

internal enum class AppLanguage {
    English,
    Dari
}

internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.English }
internal val LocalAppTranslations = staticCompositionLocalOf { AppTranslations.Empty }

internal class AppTranslations private constructor(
    private val exact: Map<String, String>,
    private val templates: List<TemplateTranslation>
) {
    fun translate(text: String): String {
        exact[text]?.let { return it }
        templates.forEach { template ->
            template.translate(text)?.let { return it }
        }
        return text
    }

    companion object {
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
            return AppTranslations(exact, templates)
        }
    }
}

internal fun loadDariTranslations(context: Context): AppTranslations {
    return runCatching {
        val raw = context.resources.openRawResource(R.raw.prs_af)
            .bufferedReader()
            .use { it.readText() }
        AppTranslations.fromJson(JSONObject(raw))
    }.getOrDefault(AppTranslations.Empty)
}

@Composable
internal fun localizeAppText(text: String): String {
    return when (LocalAppLanguage.current) {
        AppLanguage.English -> text
        AppLanguage.Dari -> LocalAppTranslations.current.translate(text)
    }
}

private data class TemplatePart(
    val literal: String? = null,
    val token: String? = null
)

private data class TemplateTranslation(
    val regex: Regex,
    val targetParts: List<TemplatePart>,
    val tokenGroups: Map<String, Int>
) {
    fun translate(text: String): String? {
        val match = regex.matchEntire(text) ?: return null
        return buildString {
            targetParts.forEach { part ->
                when {
                    part.literal != null -> append(part.literal)
                    part.token != null -> {
                        val group = tokenGroups[part.token]
                        if (group != null && group < match.groupValues.size) {
                            append(match.groupValues[group])
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
            regex = Regex(pattern),
            targetParts = parseTemplate(target),
            tokenGroups = tokenGroups
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
