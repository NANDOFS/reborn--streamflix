package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class MStreamClickExtractor : Extractor() {
    override val name = Base64.decode(
        "bW9mbGl4LQ==", Base64.NO_WRAP
    ).toString(Charsets.UTF_8) + Base64.decode(
        "c3RyZWFtLmNsaWNr", Base64.NO_WRAP
    ).toString(Charsets.UTF_8)
    override val mainUrl = Base64.decode(
        "aHR0cHM6Ly9tb2ZsaXgt", Base64.NO_WRAP
    ).toString(Charsets.UTF_8) + Base64.decode(
        "c3RyZWFtLmNsaWNrLw==", Base64.NO_WRAP
    ).toString(Charsets.UTF_8)

    override suspend fun extract(link: String): Video {
        val service = MStreamClickExtractorService.build(mainUrl, link)
        val source = service.getSource(link.replace(mainUrl, ""))
        val html = source.html()
        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(html)
            ?.groupValues?.get(1)
            ?: throw Exception("Packed JS not found")
        val script = JsUnpacker(packedJS).unpack() ?: html

        val m3u8 = Regex("""["']hls(?:2|3|4)["']\s*:\s*["']([^"']*?\.m3u8[^"']*)["']""")
            .find(script)
            ?.groupValues?.getOrNull(1)
            ?: throw Exception("Can't find m3u8")
        return Video(source = m3u8)
    }

    private interface MStreamClickExtractorService {
        companion object {
            fun build(baseUrl: String, originalLink: String): MStreamClickExtractorService {
                val retrofit = Retrofit.Builder().baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create()).build()
                return retrofit.create(MStreamClickExtractorService::class.java)
            }
        }

        @GET
        suspend fun getSource(@Url url: String): Document
    }
}