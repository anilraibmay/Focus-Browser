package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import java.net.URLDecoder
import java.util.regex.Pattern

sealed class ParsedQr {
    data class Url(val url: String) : ParsedQr()
    data class Wifi(val ssid: String, val password: String, val securityType: String = "WPA", val isHidden: Boolean = false) : ParsedQr()
    data class Contact(
        val name: String,
        val phone: String? = null,
        val email: String? = null,
        val organization: String? = null,
        val website: String? = null,
        val rawVCard: String = ""
    ) : ParsedQr()
    data class Email(val email: String, val subject: String? = null, val body: String? = null) : ParsedQr()
    data class Phone(val number: String) : ParsedQr()
    data class Sms(val number: String, val message: String? = null) : ParsedQr()
    data class Geo(val latitude: Double, val longitude: Double, val label: String? = null) : ParsedQr()
    data class PlainText(val text: String) : ParsedQr()
}

enum class QrStandardType(val title: String, val subtitle: String) {
    LINK_TEXT("Link / URL", "Links da web ou qualquer texto livre"),
    WIFI("Rede Wi-Fi", "Conexão automática com SSID e senha"),
    VCARD("Contacto (vCard)", "Cartão de visita com telefone e e-mail"),
    EMAIL("E-mail", "Envio rápido de mensagem com assunto"),
    PHONE("Telefone", "Número para chamada telefónica"),
    SMS("SMS", "Mensagem de texto rápida"),
    GEO("Localização", "Coordenadas geográficas no mapa")
}

object QrStandardsHelper {

    /**
     * Analisa uma string de QR Code e identifica o padrão da indústria correspondente.
     */
    fun parse(raw: String): ParsedQr {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedQr.PlainText("")

        // 1. Wi-Fi (WIFI:S:SSID;T:WPA;P:Password;H:false;;)
        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            val ssid = extractWifiField(trimmed, "S")
            val password = extractWifiField(trimmed, "P")
            val type = extractWifiField(trimmed, "T").ifEmpty { "WPA" }
            val hidden = extractWifiField(trimmed, "H").equals("true", ignoreCase = true)
            return ParsedQr.Wifi(ssid = ssid, password = password, securityType = type, isHidden = hidden)
        }

        // 2. vCard ou MECARD (BEGIN:VCARD ... END:VCARD ou MECARD:N:...)
        if (trimmed.contains("BEGIN:VCARD", ignoreCase = true)) {
            return parseVCard(trimmed)
        }
        if (trimmed.startsWith("MECARD:", ignoreCase = true)) {
            return parseMeCard(trimmed)
        }

        // 3. E-mail (mailto: ou MATMSG:)
        if (trimmed.startsWith("mailto:", ignoreCase = true)) {
            return parseMailTo(trimmed)
        }
        if (trimmed.startsWith("MATMSG:", ignoreCase = true)) {
            return parseMatMsg(trimmed)
        }

        // 4. Telefone (tel:)
        if (trimmed.startsWith("tel:", ignoreCase = true)) {
            val num = trimmed.substring(4).trim()
            return ParsedQr.Phone(number = num)
        }

        // 5. SMS (smsto: ou sms:)
        if (trimmed.startsWith("smsto:", ignoreCase = true) || trimmed.startsWith("sms:", ignoreCase = true)) {
            val clean = if (trimmed.startsWith("smsto:", ignoreCase = true)) trimmed.substring(6) else trimmed.substring(4)
            val parts = clean.split(":", limit = 2)
            val num = parts.getOrNull(0) ?: ""
            val msg = parts.getOrNull(1)
            return ParsedQr.Sms(number = num, message = msg)
        }

        // 6. Geolocalização (geo:latitude,longitude)
        if (trimmed.startsWith("geo:", ignoreCase = true)) {
            val clean = trimmed.substring(4)
            val parts = clean.split("?")[0].split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val label = if (clean.contains("q=")) clean.substringAfter("q=") else null
                return ParsedQr.Geo(latitude = lat, longitude = lng, label = label)
            }
        }

        // 7. URL (http://, https://, www., ou formato de link)
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("www.", ignoreCase = true)
        ) {
            val validUrl = if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
            return ParsedQr.Url(url = validUrl)
        }

        return ParsedQr.PlainText(text = trimmed)
    }

    // --- Builders para Geração Padronizada ---

    fun buildWifiString(ssid: String, password: String, type: String = "WPA", isHidden: Boolean = false): String {
        val cleanType = if (type.isBlank()) "WPA" else type
        val hiddenStr = if (isHidden) "H:true;" else ""
        return "WIFI:T:$cleanType;S:${escapeWifi(ssid)};P:${escapeWifi(password)};$hiddenStr;"
    }

    fun buildVCardString(name: String, phone: String?, email: String?, organization: String?, website: String?): String {
        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:${name.trim()}")
            appendLine("N:${name.trim()};;;;")
            if (!phone.isNullOrBlank()) appendLine("TEL;TYPE=CELL:${phone.trim()}")
            if (!email.isNullOrBlank()) appendLine("EMAIL;TYPE=INTERNET:${email.trim()}")
            if (!organization.isNullOrBlank()) appendLine("ORG:${organization.trim()}")
            if (!website.isNullOrBlank()) appendLine("URL:${website.trim()}")
            append("END:VCARD")
        }
    }

    fun buildEmailString(email: String, subject: String?, body: String?): String {
        val queryParams = mutableListOf<String>()
        if (!subject.isNullOrBlank()) queryParams.add("subject=${Uri.encode(subject)}")
        if (!body.isNullOrBlank()) queryParams.add("body=${Uri.encode(body)}")
        val query = if (queryParams.isNotEmpty()) "?" + queryParams.joinToString("&") else ""
        return "mailto:${email.trim()}$query"
    }

    fun buildPhoneString(number: String): String {
        return "tel:${number.trim()}"
    }

    fun buildSmsString(number: String, message: String?): String {
        return if (!message.isNullOrBlank()) "smsto:${number.trim()}:${message.trim()}"
        else "smsto:${number.trim()}"
    }

    fun buildGeoString(lat: Double, lng: Double, label: String? = null): String {
        return if (!label.isNullOrBlank()) "geo:$lat,$lng?q=${Uri.encode(label)}"
        else "geo:$lat,$lng"
    }

    // --- Intents de Ação ---

    fun openWifiSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun addContact(context: Context, contact: ParsedQr.Contact) {
        try {
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
                contact.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                contact.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                contact.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun dialPhone(context: Context, number: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun sendSms(context: Context, number: String, message: String?) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (!message.isNullOrBlank()) putExtra("sms_body", message)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun sendEmail(context: Context, email: String, subject: String?, body: String?) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openMap(context: Context, lat: Double, lng: Double, label: String?) {
        try {
            val uriStr = if (!label.isNullOrBlank()) "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"
            else "geo:$lat,$lng?q=$lat,$lng"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    // --- Helpers de Parsing Privados ---

    private fun extractWifiField(qr: String, key: String): String {
        val pattern = Pattern.compile("(?:^|;)$key:([^;]*)(?:;|\$)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(qr)
        return if (matcher.find()) matcher.group(1)?.unescapeWifi() ?: "" else ""
    }

    private fun escapeWifi(value: String): String {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(":", "\\:").replace(",", "\\,")
    }

    private fun String.unescapeWifi(): String {
        return this.replace("\\;", ";").replace("\\:", ":").replace("\\,", ",").replace("\\\\", "\\")
    }

    private fun parseVCard(vcard: String): ParsedQr.Contact {
        var name = ""
        var phone: String? = null
        var email: String? = null
        var org: String? = null
        var website: String? = null

        vcard.lines().forEach { line ->
            val clean = line.trim()
            when {
                clean.startsWith("FN:", ignoreCase = true) -> name = clean.substring(3).trim()
                clean.startsWith("N:", ignoreCase = true) && name.isBlank() -> {
                    val parts = clean.substring(2).split(";")
                    name = parts.filter { it.isNotBlank() }.reversed().joinToString(" ")
                }
                clean.startsWith("TEL", ignoreCase = true) -> {
                    phone = clean.substringAfter(":", "").trim().takeIf { it.isNotBlank() }
                }
                clean.startsWith("EMAIL", ignoreCase = true) -> {
                    email = clean.substringAfter(":", "").trim().takeIf { it.isNotBlank() }
                }
                clean.startsWith("ORG:", ignoreCase = true) -> org = clean.substring(4).trim()
                clean.startsWith("URL:", ignoreCase = true) -> website = clean.substring(4).trim()
            }
        }
        return ParsedQr.Contact(
            name = name.ifBlank { "Contacto" },
            phone = phone,
            email = email,
            organization = org,
            website = website,
            rawVCard = vcard
        )
    }

    private fun parseMeCard(mecard: String): ParsedQr.Contact {
        val name = extractMeCardField(mecard, "N")
        val phone = extractMeCardField(mecard, "TEL").takeIf { it.isNotBlank() }
        val email = extractMeCardField(mecard, "EMAIL").takeIf { it.isNotBlank() }
        val org = extractMeCardField(mecard, "ORG").takeIf { it.isNotBlank() }
        val url = extractMeCardField(mecard, "URL").takeIf { it.isNotBlank() }
        return ParsedQr.Contact(
            name = name.ifBlank { "Contacto" },
            phone = phone,
            email = email,
            organization = org,
            website = url,
            rawVCard = mecard
        )
    }

    private fun extractMeCardField(raw: String, key: String): String {
        val pattern = Pattern.compile("(?:^|;)$key:([^;]*)(?:;|\$)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(raw)
        return if (matcher.find()) matcher.group(1)?.trim() ?: "" else ""
    }

    private fun parseMailTo(mailto: String): ParsedQr.Email {
        val clean = mailto.substring(7)
        val emailPart = clean.substringBefore("?")
        val queryPart = if (clean.contains("?")) clean.substringAfter("?") else ""
        var subject: String? = null
        var body: String? = null

        queryPart.split("&").forEach { param ->
            val pair = param.split("=")
            if (pair.size == 2) {
                val key = pair[0].lowercase()
                val value = try { URLDecoder.decode(pair[1], "UTF-8") } catch (_: Exception) { pair[1] }
                if (key == "subject") subject = value
                if (key == "body") body = value
            }
        }
        return ParsedQr.Email(email = emailPart, subject = subject, body = body)
    }

    private fun parseMatMsg(matmsg: String): ParsedQr.Email {
        val email = extractMatMsgField(matmsg, "TO")
        val sub = extractMatMsgField(matmsg, "SUB").takeIf { it.isNotBlank() }
        val body = extractMatMsgField(matmsg, "BODY").takeIf { it.isNotBlank() }
        return ParsedQr.Email(email = email, subject = sub, body = body)
    }

    private fun extractMatMsgField(raw: String, key: String): String {
        val pattern = Pattern.compile("(?:^|;)$key:([^;]*)(?:;|\$)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(raw)
        return if (matcher.find()) matcher.group(1)?.trim() ?: "" else ""
    }
}
