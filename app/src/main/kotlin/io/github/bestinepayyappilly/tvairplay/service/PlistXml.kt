package io.github.bestinepayyappilly.tvairplay.service

import android.util.Base64
import android.util.Xml
import io.github.bestinepayyappilly.tvairplay.bridge.NativeBridge
import org.xmlpull.v1.XmlPullParser

/**
 * XML property lists to and from Kotlin values, for AirPlay video v2 messages: dict -> Map,
 * array -> List, string, integer -> Long, real -> Double, true/false, data -> ByteArray.
 * Binary plists go through libplist (NativeBridge) first.
 */
object PlistXml {

    fun parse(xml: String): Any? {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name != "plist") return _value(p)
        }
        return null
    }

    fun parseBinary(bin: ByteArray): Any? =
        NativeBridge.plistBinToXml(bin)?.let { parse(String(it, Charsets.UTF_8)) }

    fun toBinary(value: Any?): ByteArray? =
        NativeBridge.plistXmlToBin(write(value).toByteArray(Charsets.UTF_8))

    fun write(value: Any?): String = StringBuilder().apply {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        append("<plist version=\"1.0\">")
        _write(this, value)
        append("</plist>")
    }.toString()

    // parser is on the START_TAG of a value; returns with it on that value's END_TAG
    private fun _value(p: XmlPullParser): Any? = when (p.name) {
        "dict" -> {
            val map = LinkedHashMap<String, Any?>()
            var key: String? = null
            while (p.next() != XmlPullParser.END_TAG) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                if (p.name == "key") key = p.nextText()
                else { val v = _value(p); key?.let { map[it] = v }; key = null }
            }
            map
        }
        "array" -> {
            val list = ArrayList<Any?>()
            while (p.next() != XmlPullParser.END_TAG) {
                if (p.eventType == XmlPullParser.START_TAG) list.add(_value(p))
            }
            list
        }
        "string", "date" -> p.nextText()
        "integer" -> p.nextText().trim().toLongOrNull()
        "real" -> p.nextText().trim().toDoubleOrNull()
        "data" -> Base64.decode(p.nextText().replace(Regex("\\s"), ""), Base64.DEFAULT)
        "true" -> { p.next(); true }
        "false" -> { p.next(); false }
        else -> { _skip(p); null }
    }

    private fun _skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) when (p.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
        }
    }

    // nulls are left out: plists have no null, a missing key stands for it
    private fun _write(sb: StringBuilder, v: Any?) {
        when (v) {
            is Map<*, *> -> {
                sb.append("<dict>")
                v.forEach { (k, value) ->
                    if (value == null) return@forEach
                    sb.append("<key>").append(_escape(k.toString())).append("</key>")
                    _write(sb, value)
                }
                sb.append("</dict>")
            }
            is List<*> -> { sb.append("<array>"); v.filterNotNull().forEach { _write(sb, it) }; sb.append("</array>") }
            is String -> sb.append("<string>").append(_escape(v)).append("</string>")
            is Boolean -> sb.append(if (v) "<true/>" else "<false/>")
            is Int, is Long, is Short, is Byte -> sb.append("<integer>").append(v).append("</integer>")
            is Float, is Double -> sb.append("<real>").append(v).append("</real>")
            is ByteArray -> sb.append("<data>").append(Base64.encodeToString(v, Base64.NO_WRAP)).append("</data>")
            null -> {}
            else -> sb.append("<string>").append(_escape(v.toString())).append("</string>")
        }
    }

    private fun _escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
