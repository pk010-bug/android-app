package app.privateinbox.policy

import app.privateinbox.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class ManifestPolicyTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private val doc by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(TestFiles.module("src/main/AndroidManifest.xml"))
    }

    private fun elements(tag: String): List<Element> =
        doc.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    @Test fun noInternetOrCallPhone() {
        val requested = elements("uses-permission")
            .filter { it.getAttributeNS(tools, "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
        assertFalse("INTERNET requested", "android.permission.INTERNET" in requested)
        assertFalse("CALL_PHONE requested", "android.permission.CALL_PHONE" in requested)
    }

    @Test fun networkPermissionsAreRefused() {
        val network = listOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE", "android.permission.CHANGE_WIFI_STATE",
        )
        val all = elements("uses-permission")
        network.forEach { p ->
            assertTrue("$p must be refused with tools:node=remove", all.any { it.getAttributeNS(android, "name") == p && it.getAttributeNS(tools, "node") == "remove" })
            assertFalse("$p requested", all.any { it.getAttributeNS(android, "name") == p && it.getAttributeNS(tools, "node") != "remove" })
        }
    }

    @Test fun exportedSurfaceIsMinimalAndPermissionGuarded() {
        val expected = mapOf(
            ".MainActivity" to "",
            ".sms.SmsReceiver" to "android.permission.BROADCAST_SMS",
            ".sms.MmsReceiver" to "android.permission.BROADCAST_WAP_PUSH",
            ".sms.HeadlessSmsSendService" to "android.permission.SEND_RESPOND_VIA_MESSAGE",
            ".widget.InboxWidgetProvider" to "android.permission.BIND_APPWIDGET",
        )
        val components = listOf("activity", "receiver", "service", "provider").flatMap(::elements)
        components.forEach {
            assertTrue("${it.getAttributeNS(android, "name")} must set android:exported explicitly", it.hasAttributeNS(android, "exported"))
        }
        val exported = components
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "permission") }
        assertEquals(expected, exported)
    }

    @Test fun backupDisabled() {
        val app = elements("application").single()
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
    }

    @Test fun noBrowsableWebDeepLinks() {
        val schemes = elements("data").map { it.getAttributeNS(android, "scheme") }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("sms", "smsto", "mms", "mmsto"), schemes)
    }

    @Test fun fileProviderPathsAreNarrowCacheDirs() {
        val xml = TestFiles.module("src/main/res/xml/file_paths.xml").readText()
        val tags = Regex("<([a-z-]+-path)\\b[^>]*path=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertEquals(setOf("cache-path" to "share/", "cache-path" to "mms/", "cache-path" to "open/"), tags)
    }

    @Test fun widgetIsHomeScreenOnly() {
        val xml = TestFiles.module("src/main/res/xml/inbox_widget_info.xml").readText()
        assertTrue(Regex("widgetCategory=\"home_screen\"").containsMatchIn(xml))
        assertFalse("keyguard" in xml.substringAfter("<appwidget-provider"))
    }
}
