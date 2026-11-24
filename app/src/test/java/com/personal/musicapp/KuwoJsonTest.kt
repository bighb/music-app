package com.personal.musicapp

import com.google.gson.JsonParser
import com.personal.musicapp.data.source.KuwoAudioHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Kuwo's r.s endpoint answers with single-quoted pseudo-JSON; these bodies mirror real replies. */
class KuwoJsonTest {

    private fun items(body: String): List<com.google.gson.JsonObject> =
        JsonParser.parseString(KuwoAudioHelper.normalizeKuwoJson(body)).asJsonObject
            .getAsJsonArray("abslist").map { it.asJsonObject }

    private fun titles(body: String): List<String> = items(body).map { it.get("SONGNAME").asString }

    @Test
    fun anApostropheInsideATitleDoesNotBreakTheWholeBatch() {
        val body = """
            {'abslist':[
              {'dc_targetid':'111','DC_TARGETID_PRECISE':'1','SONGNAME':'Don't Stop','ARTIST':'Oasis','DURATION':'257','MUSICRID':'MUSIC_111','web_albumpic_short':''},
              {'dc_targetid':'222','DC_TARGETID_PRECISE':'1','SONGNAME':'爱'你','ARTIST':'群星','DURATION':'240','MUSICRID':'MUSIC_222','web_albumpic_short':'20240101/11/22/abc.jpg'}
            ],'TOTAL':'2','PERHITINFO':'1'}
        """.trimIndent()
        assertEquals(listOf("Don't Stop", "爱'你"), titles(body))
        val items = items(body)
        assertEquals("111", items[0].get("dc_targetid").asString)
        assertEquals("MUSIC_222", items[1].get("MUSICRID").asString)
        assertEquals("20240101/11/22/abc.jpg", items[1].get("web_albumpic_short").asString)
    }

    @Test
    fun anApostropheRightBeforeTheClosingQuoteIsStillContent() {
        val body = "{'abslist':[{'dc_targetid':'1','SONGNAME':'Rock 'n' Roll','ARTIST':'Led Zeppelin','DURATION':'220'}]}"
        assertEquals(listOf("Rock 'n' Roll"), titles(body))
    }

    @Test
    fun doubleQuotesInsideATitleAreEscapedNotDropped() {
        val body = "{'abslist':[{'dc_targetid':'1','SONGNAME':'说\"你好\"','ARTIST':'A B','DURATION':'200'}]}"
        assertEquals(listOf("说\"你好\""), titles(body))
    }

    @Test
    fun aBodyWithoutSingleQuotesIsUntouched() {
        val json = """{"abslist":[{"SONGNAME":"晴天"}]}"""
        assertSame(json, KuwoAudioHelper.normalizeKuwoJson(json))
        assertEquals(listOf("晴天"), titles(json))
    }

    @Test
    fun htmlEntitiesInTitlesAreDecoded() {
        assertEquals("Don't Stop", KuwoAudioHelper.cleanHtmlEntities("Don&apos;t Stop"))
        assertEquals("Don't Stop", KuwoAudioHelper.cleanHtmlEntities("Don&#39;t Stop"))
        assertEquals("A & B \"C\" <D>", KuwoAudioHelper.cleanHtmlEntities("A &amp; B &quot;C&quot; &lt;D&gt;"))
        assertEquals("晴天", KuwoAudioHelper.cleanHtmlEntities("  晴天 "))
    }

    @Test
    fun titleFallbackIsCappedAtTwoDiverseQueries() {
        assertEquals(listOf("晴天 (Live) 周杰伦", "晴天"), KuwoAudioHelper.titleQueries("晴天 (Live)", "周杰伦"))
        assertEquals(listOf("晴天【Live版】 周杰伦", "晴天"), KuwoAudioHelper.titleQueries("晴天【Live版】", "周杰伦"))
        assertEquals(listOf("晴天 周杰伦", "晴天"), KuwoAudioHelper.titleQueries("晴天", "周杰伦"))
        assertEquals(listOf("晴天", "晴天 (Live)"), KuwoAudioHelper.titleQueries("晴天 (Live)", "群星"))
        assertEquals(listOf("晴天 A", "晴天"), KuwoAudioHelper.titleQueries("晴天", "A/B/C"))
        assertEquals(listOf("晴天"), KuwoAudioHelper.titleQueries("晴天", ""))
    }

    @Test
    fun theAntiServerUrlKeepsTheFreeFormat() {
        assertEquals(
            "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=111&format=mp3&response=url",
            KuwoAudioHelper.antiServerUrl("111")
        )
    }
}
