package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DecryptHelper
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class VoeExtractor : Extractor() {

    override val name = "VOE"
    override val mainUrl = "https://voe.sx/"
    override val aliasUrls = listOf("https://jilliandescribecompany.com")


    override suspend fun extract(link: String): Video {
        val service = VoeExtractorService.build(mainUrl, link)
        val source = service.getSource(link.replace(mainUrl, ""))
        val scriptTag = source.selectFirst("script[type=application/json]")
        val encodedStringInScriptTag = scriptTag?.data()?.trim().orEmpty()
        val encodedString = DecryptHelper.findEncodedRegex(source.html())
        val decryptedContent = if (encodedString != null) {
            DecryptHelper.decrypt(encodedString)
        } else {
            DecryptHelper.decrypt(encodedStringInScriptTag)
        }
        val m3u8 = decryptedContent.get("source")?.asString.orEmpty()

        return Video(
            source = m3u8,
            subtitles = listOf()
        )

    }


    private interface VoeExtractorService {

        companion object {
            suspend fun build(baseUrl: String, originalLink: String): VoeExtractorService {
                val retrofitVOE = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                val retrofitVOEBuiled = retrofitVOE.create(VoeExtractorService::class.java)

                val retrofitVOEhtml =
                    retrofitVOEBuiled.getSource(originalLink.replace(baseUrl, "")).html()

                val regex = Regex("""https://([a-zA-Z0-9.-]+)(?:/[^'"]*)?""")
                val match = regex.find(retrofitVOEhtml)
                val redirectBaseUrl = if (match != null) {
                    "https://${match.groupValues[1]}/"
                } else {
                    throw Exception("Base url not found for VOE")
                }
                val retrofitRedirected = Retrofit.Builder()
                    .baseUrl(redirectBaseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofitRedirected.create(VoeExtractorService::class.java)
            }
        }

        @GET
        suspend fun getSource(@Url url: String): Document
    }
}
