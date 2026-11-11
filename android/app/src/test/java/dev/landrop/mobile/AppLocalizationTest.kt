package dev.landrop.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocalizationTest {
    @Test fun appGeneratedFailureReasonIsLocalizedInsideConnectionMessage() {
        val catalog = JSONObject().put("shared", JSONObject()
            .put("Wi-Fi Direct QR connect failed: ${'$'}{connectFailure(reason)}", "Error al conectar: ${'$'}{connectFailure(reason)}")
            .put("Wi-Fi Direct discovery failed: ${'$'}{discoveryFailure(reason)}", "Error al buscar: ${'$'}{discoveryFailure(reason)}")
            .put("Selected ${'$'}{peer.name} at ${'$'}{peer.ip}:${'$'}{peer.port} (${'$'}link).", "Seleccionado ${'$'}{peer.name} en ${'$'}{peer.ip}:${'$'}{peer.port} (${'$'}link).")
            .put("Secure", "Seguro")
            .put("internal error, toggle Wi-Fi and retry", "error interno, reinicia el Wi-Fi y vuelve a intentarlo"))
        val translations = AppTranslations.fromJson(catalog)
        assertEquals("Error al conectar: error interno, reinicia el Wi-Fi y vuelve a intentarlo",
            translations.translate("Wi-Fi Direct QR connect failed: internal error, toggle Wi-Fi and retry"))
        assertEquals("Error al buscar: error interno, reinicia el Wi-Fi y vuelve a intentarlo",
            translations.translate("Wi-Fi Direct discovery failed: internal error, toggle Wi-Fi and retry"))
        assertEquals("Seleccionado Desk-PC en 100.64.0.2:50501 (Seguro).",
            translations.translate("Selected Desk-PC at 100.64.0.2:50501 (Secure)."))
        assertEquals("Error al conectar: External provider detail", translations.translate("Wi-Fi Direct QR connect failed: External provider detail"))
    }

    @Test fun deviceNameIsNotTreatedAsAStatus() {
        val catalog = JSONObject().put("android", JSONObject()
            .put("Connected to ${'$'}{remotePeer.name}", "Conectado a ${'$'}{remotePeer.name}")
            .put("Not connected", "Sin conexión"))
        assertEquals("Conectado a Not connected", AppTranslations.fromJson(catalog).translate("Connected to Not connected"))
    }

    @Test fun templatesPreserveValuesWhenTranslationReordersTokens() {
        val source = "From ${'$'}sender to ${'$'}receiver"
        val target = "${'$'}receiver ← ${'$'}sender"
        val translations = AppTranslations.fromJson(JSONObject().put("shared", JSONObject().put(source, target)))
        assertEquals("PC-漢 ← Phone", translations.translate("From Phone to PC-漢"))
        assertEquals("Untouched file.jpg", translations.translate("Untouched file.jpg"))
    }

    @Test fun complexTemplateExpressionsRemainNamedTokens() {
        val source = "Trusted devices: ${'$'}{maxOf(ids.size, ips.size)}"
        val translations = AppTranslations.fromJson(JSONObject().put("android", JSONObject().put(source, "${'$'}{maxOf(ids.size, ips.size)} Geräte")))
        assertEquals("12 Geräte", translations.translate("Trusted devices: 12"))
    }

    @Test fun savedLanguageNamesRemainCompatible() {
        assertEquals(AppLanguage.English, AppLanguage.valueOf("English"))
        assertEquals(AppLanguage.Dari, AppLanguage.valueOf("Dari"))
        assertEquals(listOf("en", "prs-AF", "ja", "es", "tr", "pt"), AppLanguage.entries.map { it.localeTag })
        assertEquals(listOf(AppLanguage.Dari), AppLanguage.entries.filter { it.rtl })
    }
}
