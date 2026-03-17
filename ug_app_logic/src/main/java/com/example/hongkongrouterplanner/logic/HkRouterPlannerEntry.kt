package com.example.hongkongrouterplanner.logic

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.DisplayOptions
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hong Kong Router Planner — xg.glass app for Rokid Glasses.
 *
 * Commands:
 * 1. Weather Report — current HKO conditions + forecast for your district
 * 2. Plan My Route — record voice destination → AI route suggestion + live KMB ETA
 * 3. Live Bus ETA — say a bus number → get nearest-stop ETA from KMB
 *
 * Settings (configure in the host app):
 * - AI API key / base URL / model (OpenAI-compatible)
 * - District is auto-detected from Android GPS by the host app
 */
class HkRouterPlannerEntry : UniversalAppEntrySimple {

    override val id = "hk-router-planner"
    override val displayName = "HK Router Planner"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    )

    override fun commands(): List<UniversalCommand> = listOf(
        weatherCommand(),
        planRouteCommand(),
        etaCommand(),
    )

    // ───────────────────────────────────────────────────────────────────────
    // COMMAND 1 — WEATHER REPORT
    // ───────────────────────────────────────────────────────────────────────

    private fun weatherCommand() = object : UniversalCommand {
        override val id = "weather"
        override val title = "Weather Report"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            ctx.client.display("Fetching HK weather…", DisplayOptions())
            val district = ctx.settings[KEY_DISTRICT] ?: "Hong Kong"
            val userLat = ctx.settings[KEY_USER_LAT]?.toDoubleOrNull()
            val userLng = ctx.settings[KEY_USER_LNG]?.toDoubleOrNull()
            val client = HttpClient()
            return try {
                val currentResult = fetchJsonObject(client, HKO_CURRENT_URL)
                val forecastResult = fetchJsonObject(client, HKO_FORECAST_URL)
                if (currentResult.isFailure || forecastResult.isFailure) {
                    val err = (currentResult.exceptionOrNull() ?: forecastResult.exceptionOrNull())
                        ?.message ?: "Unknown error"
                    Log.e(TAG, "Weather fetch failed: $err")
                    return ctx.client.display(
                        "Weather unavailable.\nError: ${err.take(120)}",
                        DisplayOptions()
                    )
                }
                val currentJson = currentResult.getOrNull()!!
                val forecastJson = forecastResult.getOrNull()!!

                // Temperature — nearest HKO station to user's GPS, fallback HK Observatory.
                val tempArr =
                    currentJson["temperature"]?.jsonObject?.get("data")?.jsonArray ?: JsonArray(emptyList())
                val tempPlaces = tempArr.mapNotNull { it.jsonObject["place"]?.jsonPrimitive?.contentOrNull }
                val nearestTempStation = resolveNearestHkoStation(tempPlaces, district, userLat, userLng)
                Log.d(TAG, "Nearest temp station: $nearestTempStation (user GPS: $userLat, $userLng)")

                val temp = tempArr.firstOrNull {
                        it.jsonObject["place"]?.jsonPrimitive?.contentOrNull == nearestTempStation
                    }?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
                    ?: tempArr.firstOrNull {
                        normalizeDistrict(it.jsonObject["place"]?.jsonPrimitive?.contentOrNull) ==
                            normalizeDistrict(nearestTempStation)
                    }?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
                    ?: tempArr.firstOrNull {
                        it.jsonObject["place"]?.jsonPrimitive?.content == "Hong Kong Observatory"
                    }?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull

                val rainfallArr =
                    currentJson["rainfall"]?.jsonObject?.get("data")?.jsonArray ?: JsonArray(emptyList())
                val rainfallPlaces = rainfallArr.mapNotNull { it.jsonObject["place"]?.jsonPrimitive?.contentOrNull }
                val nearestRainfallStation = resolveNearestHkoStation(rainfallPlaces, district, userLat, userLng)
                Log.d(TAG, "Nearest rainfall station: $nearestRainfallStation")

                val rainfallDistrict = rainfallArr.firstOrNull { entry ->
                    val place = entry.jsonObject["place"]?.jsonPrimitive?.contentOrNull
                    place == nearestRainfallStation ||
                        normalizeDistrict(place) == normalizeDistrict(nearestRainfallStation)
                }?.jsonObject
                val rainfallMax = rainfallDistrict?.get("max")?.jsonPrimitive?.intOrNull

                val humidity =
                    currentJson["humidity"]?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject?.get(
                            "value"
                        )?.jsonPrimitive?.intOrNull

                val uvDesc =
                    currentJson["uvindex"]?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject?.get(
                            "desc"
                        )?.jsonPrimitive?.contentOrNull

                val warnings =
                    currentJson["warningMessage"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter { it.isNotBlank() } ?: emptyList()

                val forecastPeriod = forecastJson["forecastPeriod"]?.jsonPrimitive?.content ?: ""
                val forecastDesc = forecastJson["forecastDesc"]?.jsonPrimitive?.content ?: ""
                val outlook = forecastJson["outlook"]?.jsonPrimitive?.content ?: ""

                ctx.client.display(buildString {
                    appendLine("=== HK Weather ===")
                    appendLine("Station: $nearestTempStation")
                    temp?.let { appendLine("Temp: ${it}°C") }
                    rainfallMax?.let { appendLine("Rainfall: ${it}mm") }
                    humidity?.let { appendLine("Humidity: ${it}%") }
                    uvDesc?.let { appendLine("UV: $it") }
                    if (warnings.isNotEmpty()) appendLine("⚠ ${warnings.joinToString("; ")}")
                    if (forecastPeriod.isNotBlank()) {
                        appendLine()
                        appendLine(forecastPeriod)
                    }
                    if (forecastDesc.isNotBlank()) appendLine(forecastDesc.take(180))
                    if (outlook.isNotBlank()) {
                        appendLine()
                        append("Outlook: ${outlook.take(130)}")
                    }
                }.trim(), DisplayOptions())
            } finally {
                client.close()
            }
        }
    }

    private suspend fun fetchJsonObject(
        client: HttpClient,
        url: String,
    ): Result<kotlinx.serialization.json.JsonObject> {
        var lastEx: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) delay(1_000L)
            val bodyResult = runCatching {
                client.get(url) {
                    // HKO blocks requests that look non-browser; mimic a real browser.
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                    header(HttpHeaders.CacheControl, "no-cache")
                    header(HttpHeaders.Referrer, "https://www.hko.gov.hk/")
                    header(
                        HttpHeaders.UserAgent,
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                    )
                }.bodyAsText()
            }
            if (bodyResult.isFailure) {
                lastEx = bodyResult.exceptionOrNull()
                Log.e(TAG, "Fetch attempt ${attempt + 1} network error for $url: ${lastEx?.message}", lastEx)
                return@repeat
            }
            val body = bodyResult.getOrNull() ?: return@repeat
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{")) {
                // Log the first 400 chars of the body so we can see what the server returned.
                val preview = trimmed.take(400).replace("\n", " ").replace("\r", "")
                val msg = "Non-JSON response (attempt ${attempt + 1}) from $url — body: $preview"
                Log.w(TAG, msg)
                lastEx = IllegalStateException("Non-JSON response from $url (attempt ${attempt + 1})")
                return@repeat
            }
            val parseResult = runCatching { Json.parseToJsonElement(trimmed).jsonObject }
            if (parseResult.isSuccess) {
                Log.d(TAG, "Fetched $url OK (attempt ${attempt + 1})")
                return Result.success(parseResult.getOrNull()!!)
            }
            lastEx = parseResult.exceptionOrNull()
            Log.e(TAG, "JSON parse error for $url (attempt ${attempt + 1})", lastEx)
        }
        val error = lastEx ?: Exception("All fetch attempts failed for $url")
        Log.e(TAG, "fetchJsonObject failed permanently for $url", error)
        return Result.failure(error)
    }

    /**
     * Given a list of place names present in an HKO API response, returns the one that best
     * matches the user's current location:
     * 1. Exact/normalised name match with the known district.
     * 2. GPS-based: nearest HKO station by coordinates using [HKO_STATION_COORDS].
     * 3. Nearest admin district from [HK_DISTRICT_CENTERS] → normalised name match.
     * 4. Original fallback district string.
     */
    private fun resolveNearestHkoStation(
        availablePlaces: List<String>,
        fallbackDistrict: String,
        userLat: Double?,
        userLng: Double?,
    ): String {
        if (availablePlaces.isEmpty()) return fallbackDistrict

        // 1. Normalised name match with user's known district
        val normalizedFallback = normalizeDistrict(fallbackDistrict)
        availablePlaces.firstOrNull { normalizeDistrict(it) == normalizedFallback }?.let { return it }

        if (userLat != null && userLng != null) {
            val userPos = LatLng(userLat, userLng)

            // 2. GPS → nearest HKO named station present in the response
            val nearest = availablePlaces
                .mapNotNull { place -> HKO_STATION_COORDS[place]?.let { coords -> place to coords } }
                .minByOrNull { (_, coords) -> distanceMeters(userPos, coords) }
            if (nearest != null) {
                Log.d(TAG, "GPS matched HKO station: ${nearest.first} (" +
                    "${distanceMeters(userPos, nearest.second).toLong()}m from user)")
                return nearest.first
            }

            // 3. Fall back to admin district boundary → normalised name match
            val nearestDistrict = HK_DISTRICT_CENTERS.entries
                .minByOrNull { (_, center) -> distanceMeters(userPos, center) }?.key
                ?: return fallbackDistrict
            availablePlaces.firstOrNull {
                normalizeDistrict(it) == normalizeDistrict(nearestDistrict)
            }?.let { return it }
        }

        return fallbackDistrict
    }

    private fun normalizeDistrict(name: String?): String {
        return name?.lowercase()?.replace("district", "")?.replace("&", "and")?.replace("-", " ")
            ?.replace("[^a-z ]".toRegex(), "")?.trim()?.replace("\\s+".toRegex(), " ") ?: ""
    }

    // ───────────────────────────────────────────────────────────────────────
    // COMMAND 2 — PLAN MY ROUTE (voice destination → AI suggestion + live ETA)
    // ───────────────────────────────────────────────────────────────────────

    private fun planRouteCommand() = object : UniversalCommand {
        override val id = "plan_route"
        override val title = "Plan My Route"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val district = ctx.settings[KEY_DISTRICT] ?: "Tsim Sha Tsui"
            val apiKey = AIApiSettings.apiKey(ctx.settings)
            val baseUrl = AIApiSettings.baseUrl(ctx.settings)
            val startLat = ctx.settings[KEY_USER_LAT]?.toDoubleOrNull()
            val startLng = ctx.settings[KEY_USER_LNG]?.toDoubleOrNull()

            if (apiKey.isBlank()) {
                return ctx.client.display(
                    "Please set your AI API key in Settings.", DisplayOptions()
                )
            }
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.client.display(
                    "Microphone not available on this device.", DisplayOptions()
                )
            }

            // Step 1 — record voice (7 s)
            ctx.client.display(
                "Say your destination…\n(7-second recording)", DisplayOptions()
            )
            val session = ctx.client.startMicrophone().getOrElse { e ->
                return ctx.client.display(
                    "Mic error: ${e.message}", DisplayOptions()
                )
            }
            val chunks = withTimeoutOrNull(7_000L) { session.audio.toList() } ?: emptyList()
            session.stop()

            if (chunks.isEmpty()) {
                return ctx.client.display("No audio captured. Try again.", DisplayOptions())
            }

            ctx.client.display("Transcribing speech…", DisplayOptions())
            val wav = buildWav(
                chunks.flatMap { it.bytes.toList() }.toByteArray(), session.format
            )

            val client = HttpClient()
            return try {
                // Step 2 — Whisper transcription via multipart POST
                val destination = whisperTranscribe(client, wav, apiKey, baseUrl).trim()
                if (destination.isBlank()) {
                    return ctx.client.display(
                        "No speech detected. Try again.", DisplayOptions()
                    )
                }

                if (startLat == null || startLng == null) {
                    return ctx.client.display(
                        "GPS location unavailable. Please allow Location permission and try again.",
                        DisplayOptions()
                    )
                }

                ctx.client.display(
                    "From: $district\nTo: $destination\nSearching routes…", DisplayOptions()
                )

                val etaDb = fetchEtaDb(client)
                if (etaDb == null) {
                    return ctx.client.display(
                        "Route database unavailable right now. Please try again.", DisplayOptions()
                    )
                }

                val (routeList, stopList) = etaDb
                val targetPoints = findDestinationPoints(stopList, destination)
                if (targetPoints.isEmpty()) {
                    return ctx.client.display(
                        "Could not map destination to nearby stops: $destination", DisplayOptions()
                    )
                }

                val start = LatLng(startLat, startLng)
                val allJourneys = targetPoints.flatMap { end ->
                        routeSearchP2P(
                            routeList = routeList,
                            stopList = stopList,
                            start = start,
                            end = end,
                            maxDepth = 2,
                        )
                    }.distinctBy { path ->
                        path.joinToString("|") {
                            "${it.routeId}/${it.on}-${it.off}"
                        }
                    }.sortedWith(compareBy<List<RouteLeg>> { it.size }.thenBy {
                        it.sumOf { leg -> leg.off - leg.on }
                    }).take(3)

                if (allJourneys.isEmpty()) {
                    return ctx.client.display(
                        "No route found within 500m walking radius and max 1 transfer.",
                        DisplayOptions()
                    )
                }

                val routeSummary = allJourneys.mapIndexed { idx, journey ->
                    val legText = journey.joinToString(" -> ") { leg ->
                        val routeObj = routeList[leg.routeId]?.jsonObject
                        val routeNo =
                            routeObj?.get("route")?.jsonPrimitive?.contentOrNull ?: leg.routeId
                        val stops = getLongestStops(routeObj)
                        val onStop = stopNameEn(
                            stopList, stops.getOrNull(leg.on) ?: ""
                        )
                        val offStop = stopNameEn(
                            stopList, stops.getOrNull(leg.off) ?: ""
                        )
                        "$routeNo ($onStop -> $offStop)"
                    }
                    "${idx + 1}. $legText"
                }

                val etaBlock = allJourneys.firstOrNull()?.firstOrNull()?.let { topLeg ->
                    val routeObj = routeList[topLeg.routeId]?.jsonObject ?: return@let null
                    val routeNo =
                        routeObj.get("route")?.jsonPrimitive?.contentOrNull ?: return@let null
                    val svc = routeObj.get("serviceType")?.jsonPrimitive?.contentOrNull ?: "1"
                    val kmbBound =
                        routeObj.get("bound")?.jsonObject?.get("kmb")?.jsonPrimitive?.contentOrNull
                    val bound = if (kmbBound?.contains("O") == true) "outbound"
                    else "inbound"
                    runCatching { fetchEtaBlock(client, routeNo, bound, svc) }.getOrNull()
                }

                ctx.client.display(buildString {
                    appendLine("=== Route Plan ===")
                    appendLine("From: $district")
                    appendLine("To: $destination")
                    appendLine()
                    appendLine("Point-to-point results (HKBus DFS method):")
                    routeSummary.forEach { appendLine(it) }
                    etaBlock?.let {
                        appendLine()
                        appendLine("Live ETA (first KMB leg):")
                        append(it)
                    }
                }.trim(), DisplayOptions())
            } finally {
                client.close()
            }
        }
    }

    private data class LatLng(val lat: Double, val lng: Double)

    private data class RouteLeg(
        val routeId: String,
        val on: Int,
        val off: Int,
    )

    private suspend fun fetchEtaDb(
        client: HttpClient
    ): Pair<Map<String, kotlinx.serialization.json.JsonElement>, Map<String, kotlinx.serialization.json.JsonElement>>? {
        val db = runCatching {
            Json.parseToJsonElement(client.get(HK_ETA_DB_URL).bodyAsText()).jsonObject
        }.getOrElse {
                runCatching {
                    Json.parseToJsonElement(
                        client.get(HK_ETA_DB_FALLBACK_URL).bodyAsText()
                    ).jsonObject
                }.getOrNull() ?: return null
            }

        val routeList = db["routeList"]?.jsonObject ?: return null
        val stopList = db["stopList"]?.jsonObject ?: return null
        return routeList to stopList
    }

    private fun findDestinationPoints(
        stopList: Map<String, kotlinx.serialization.json.JsonElement>,
        destination: String,
    ): List<LatLng> {
        val query = destination.lowercase().trim()
        if (query.isBlank()) return emptyList()
        val queryWords = query.split(" ", ",", ".", "-").filter { it.length >= 2 }

        return stopList.values.mapNotNull { stopEl ->
                val stopObj = stopEl.jsonObject
                val nameObj = stopObj["name"]?.jsonObject ?: return@mapNotNull null
                val en = nameObj["en"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                val zh = nameObj["zh"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                val loc = stopObj["location"]?.jsonObject ?: return@mapNotNull null
                val lat = loc["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lng = loc["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null

                val score = when {
                    en.contains(query) || zh.contains(query) -> 200
                    else -> queryWords.count { en.contains(it) || zh.contains(it) } * 20
                }
                if (score <= 0) return@mapNotNull null
                score to LatLng(lat, lng)
            }.sortedByDescending { it.first }.map { it.second }.take(3)
    }

    private fun routeSearchP2P(
        routeList: Map<String, kotlinx.serialization.json.JsonElement>,
        stopList: Map<String, kotlinx.serialization.json.JsonElement>,
        start: LatLng,
        end: LatLng,
        maxDepth: Int,
    ): List<List<RouteLeg>> {
        val routeStopArrays = routeList.mapValues { (_, routeEl) ->
            routeEl.jsonObject["stops"]?.jsonObject?.values?.mapNotNull { arr ->
                arr.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            } ?: emptyList()
        }

        val longestStops =
            routeStopArrays.mapValues { (_, v) -> v.maxByOrNull { it.size } ?: emptyList() }
        val stopRoute = mutableMapOf<String, MutableSet<String>>()
        routeStopArrays.forEach { (routeId, stopArrays) ->
            stopArrays.forEach { stops ->
                stops.forEach { stopId ->
                    stopRoute.getOrPut(stopId) { mutableSetOf() }.add(routeId)
                }
            }
        }

        val routeLv = mutableMapOf<String, Int>()
        val bestRouteStop = mutableMapOf<String, Pair<Int, Double>>()
        val dfsRoutes = mutableListOf<String>()
        val allResults = mutableListOf<List<RouteLeg>>()

        fun nearbyStops(location: LatLng): List<String> =
            stopList.entries.mapNotNull { (stopId, stopEl) ->
                val loc = stopEl.jsonObject["location"]?.jsonObject ?: return@mapNotNull null
                val lat = loc["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lng = loc["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (distanceMeters(location, LatLng(lat, lng)) < WALKING_RADIUS_M) stopId
                else null
            }

        fun submitResult() {
            val parsed = dfsRoutes.mapNotNull { path ->
                val legs = path.split("|").filter { it.isNotBlank() }.mapNotNull { legPart ->
                    parseRouteLeg(legPart)
                }
                if (legs.isEmpty()) null else legs
            }

            parsed.forEach { routePath ->
                val last = routePath.last()
                val stops = longestStops[last.routeId] ?: return@forEach
                val offStopId = stops.getOrNull(last.off) ?: return@forEach
                val offLocObj =
                    stopList[offStopId]?.jsonObject?.get("location")?.jsonObject ?: return@forEach
                val offLat = offLocObj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val offLng = offLocObj["lng"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val dist = distanceMeters(end, LatLng(offLat, offLng))
                val current = bestRouteStop[last.routeId]
                if (current == null || dist < current.second) {
                    bestRouteStop[last.routeId] = last.off to dist
                }
            }

            parsed.filterTo(allResults) { routePath ->
                val last = routePath.last()
                bestRouteStop[last.routeId]?.first == last.off
            }
            dfsRoutes.clear()
        }

        fun dfs(
            curLocation: LatLng,
            targetLocation: LatLng,
            curDepth: Int,
            maxDepthAtRun: Int,
            routeFrom: String = "",
            tmpRoute: String = "",
            routePrev: MutableMap<String, MutableSet<String>>,
        ): Boolean {
            if (distanceMeters(curLocation, targetLocation) < WALKING_RADIUS_M) {
                if (curDepth == 0) {
                    dfsRoutes.add(tmpRoute)
                    routeLv[routeFrom] = maxDepthAtRun - curDepth
                }
                return true
            }
            if (curDepth == 0) return false

            val nearbyStopIds = nearbyStops(curLocation)
            val availableRoutes = mutableMapOf<String, Pair<Int, String>>()

            nearbyStopIds.forEach { stopId ->
                stopRoute[stopId]?.forEach { routeId ->
                    val routeStops = routeStopArrays[routeId].orEmpty()
                    val seq = routeStops.fold(-1) { best, stopSequence ->
                        maxOf(best, stopSequence.indexOf(stopId))
                    }
                    if (seq < 0) return@forEach

                    val prev = availableRoutes[routeId]
                    if (prev == null || prev.first > seq) {
                        availableRoutes[routeId] = seq to stopId
                    }
                }
            }

            var found = false
            availableRoutes.forEach { (routeId, pair) ->
                val takeOnStopId = pair.second
                val routeFromSet = routePrev.getOrPut(routeId) { mutableSetOf() }
                if (routeFromSet.contains(routeFrom)) return@forEach
                if ((routeLv[routeId] ?: Int.MAX_VALUE) < maxDepthAtRun) return@forEach

                routeFromSet.add(routeFrom)

                for (stops in routeStopArrays[routeId].orEmpty()) {
                    val stopIdx = stops.indexOf(takeOnStopId)
                    if (stopIdx < 0) continue

                    for (idx in (stopIdx + 1) until stops.size) {
                        val nextStopId = stops[idx]
                        val nextLoc =
                            stopList[nextStopId]?.jsonObject?.get("location")?.jsonObject?.let { loc ->
                                    val lat = loc["lat"]?.jsonPrimitive?.doubleOrNull
                                    val lng = loc["lng"]?.jsonPrimitive?.doubleOrNull
                                    if (lat != null && lng != null) LatLng(lat, lng)
                                    else null
                                } ?: continue

                        if (dfs(
                                curLocation = nextLoc,
                                targetLocation = targetLocation,
                                curDepth = curDepth - 1,
                                maxDepthAtRun = maxDepthAtRun,
                                routeFrom = routeId,
                                tmpRoute = "$tmpRoute|$routeId/$stopIdx-$idx",
                                routePrev = routePrev,
                            )
                        ) {
                            routeLv[routeFrom] = maxDepthAtRun - curDepth
                            found = true
                        }
                    }
                }
            }
            return found
        }

        for (depth in 1..maxDepth) {
            val routePrev = mutableMapOf<String, MutableSet<String>>()
            dfs(
                curLocation = start,
                targetLocation = end,
                curDepth = depth,
                maxDepthAtRun = depth,
                routePrev = routePrev,
            )
            submitResult()
        }

        return allResults
    }

    private fun parseRouteLeg(legPart: String): RouteLeg? {
        val parts = legPart.split("/")
        if (parts.size != 2) return null
        val idxParts = parts[1].split("-")
        if (idxParts.size != 2) return null
        val on = idxParts[0].toIntOrNull() ?: return null
        val off = idxParts[1].toIntOrNull() ?: return null
        return RouteLeg(parts[0], on, off)
    }

    private fun getLongestStops(routeObj: kotlinx.serialization.json.JsonObject?): List<String> {
        if (routeObj == null) return emptyList()
        return routeObj["stops"]?.jsonObject?.values?.mapNotNull { v -> v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
            ?.maxByOrNull { it.size } ?: emptyList()
    }

    private fun stopNameEn(
        stopList: Map<String, kotlinx.serialization.json.JsonElement>,
        stopId: String,
    ): String {
        if (stopId.isBlank()) return "stop"
        return stopList[stopId]?.jsonObject?.get("name")?.jsonObject?.get("en")?.jsonPrimitive?.contentOrNull
            ?: stopId
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = Math.toRadians(b.lat - a.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val x = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }

    // ───────────────────────────────────────────────────────────────────────
    // COMMAND 3 — LIVE BUS ETA (say a bus number)
    // ───────────────────────────────────────────────────────────────────────

    private fun etaCommand() = object : UniversalCommand {
        override val id = "bus_eta"
        override val title = "Live Bus ETA"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val district = ctx.settings[KEY_DISTRICT] ?: "Tsim Sha Tsui"
            val apiKey = AIApiSettings.apiKey(ctx.settings)
            val baseUrl = AIApiSettings.baseUrl(ctx.settings)
            val model = AIApiSettings.model(ctx.settings)

            if (apiKey.isBlank()) {
                return ctx.client.display(
                    "Please set your AI API key in Settings.", DisplayOptions()
                )
            }
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.client.display(
                    "Microphone not available on this device.", DisplayOptions()
                )
            }

            ctx.client.display("Say a bus number…\n(5-second recording)", DisplayOptions())
            val session = ctx.client.startMicrophone().getOrElse { e ->
                return ctx.client.display(
                    "Mic error: ${e.message}", DisplayOptions()
                )
            }
            val chunks = withTimeoutOrNull(5_000L) { session.audio.toList() } ?: emptyList()
            session.stop()

            if (chunks.isEmpty()) {
                return ctx.client.display("No audio captured. Try again.", DisplayOptions())
            }

            ctx.client.display("Processing…", DisplayOptions())
            val wav = buildWav(
                chunks.flatMap { it.bytes.toList() }.toByteArray(), session.format
            )

            val client = HttpClient()
            return try {
                val voiceText = whisperTranscribe(client, wav, apiKey, baseUrl).trim()
                if (voiceText.isBlank()) {
                    return ctx.client.display(
                        "No speech detected. Try again.", DisplayOptions()
                    )
                }

                ctx.client.display("Finding ETA for: $voiceText", DisplayOptions())

                // Extract route number via AI
                val openAI = OpenAI(token = apiKey, host = OpenAIHost(baseUrl))
                val routeNum = openAI.chatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(model),
                        messages = listOf(
                            ChatMessage(
                                role = ChatRole.User,
                                content = """Extract the KMB bus route number from: "$voiceText"
User is near $district. Reply with ONLY the route number (e.g. 1, 1A, 234X). Nothing else.""",
                            )
                        ),
                        maxTokens = 10,
                    )
                ).choices.firstOrNull()?.message?.content?.trim()?.uppercase() ?: ""

                if (routeNum.isBlank()) {
                    return ctx.client.display(
                        "Could not identify a route from: $voiceText", DisplayOptions()
                    )
                }

                // First stop of outbound route
                val stopArr = Json.parseToJsonElement(
                    client.get(
                        "$KMB_BASE/route-stop/$routeNum/outbound/1"
                    ).bodyAsText()
                ).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())

                val stopId = stopArr.firstOrNull()?.jsonObject?.get("stop")?.jsonPrimitive?.content
                    ?: return ctx.client.display(
                        "No stops found for bus $routeNum.", DisplayOptions()
                    )

                // Stop name
                val stopName = Json.parseToJsonElement(
                    client.get("$KMB_BASE/stop/$stopId").bodyAsText()
                ).jsonObject["data"]?.jsonObject?.get("name_en")?.jsonPrimitive?.contentOrNull
                    ?: stopId

                val etaBlock =
                    runCatching { fetchEtaBlock(client, routeNum, "outbound", "1") }.getOrNull()
                        ?: "No ETA available."

                ctx.client.display(buildString {
                    appendLine("=== Bus ETA ===")
                    appendLine("KMB Bus: $routeNum")
                    appendLine("Stop: $stopName")
                    appendLine()
                    append(etaBlock)
                }.trim(), DisplayOptions())
            } finally {
                client.close()
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Transcribe WAV bytes using the OpenAI Whisper API via multipart POST. Returns the transcribed
     * text or empty string on failure.
     */
    private suspend fun whisperTranscribe(
        client: HttpClient,
        wav: ByteArray,
        apiKey: String,
        baseUrl: String,
    ): String {
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        val resp = client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                formData {
                    append("model", "whisper-1")
                    append("language", "en")
                    append(
                        "file", wav, Headers.build {
                            append(HttpHeaders.ContentType, "audio/wav")
                            append(
                                HttpHeaders.ContentDisposition, "filename=\"audio.wav\""
                            )
                        })
                }))
        }
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    /**
     * Fetch ETA lines for the first stop of a KMB route. Returns a multi-line string like "5 min →
     * STAR FERRY\n20 min → STAR FERRY"
     */
    private suspend fun fetchEtaBlock(
        client: HttpClient,
        routeNum: String,
        bound: String,
        svcType: String,
    ): String {
        val stops = Json.parseToJsonElement(
            client.get("$KMB_BASE/route-stop/$routeNum/$bound/$svcType").bodyAsText()
        ).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())

        val stopId = stops.firstOrNull()?.jsonObject?.get("stop")?.jsonPrimitive?.content
            ?: return "No stops found."

        val etaArr = Json.parseToJsonElement(
            client.get("$KMB_BASE/eta/$stopId/$routeNum/$svcType").bodyAsText()
        ).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())

        val lines = etaArr.take(3).mapNotNull { e ->
            val ts = e.jsonObject["eta"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val dest = e.jsonObject["dest_en"]?.jsonPrimitive?.contentOrNull ?: ""
            val rmk = e.jsonObject["rmk_en"]?.jsonPrimitive?.contentOrNull ?: ""
            runCatching {
                val dt = OffsetDateTime.parse(ts)
                val mins = Duration.between(OffsetDateTime.now(dt.offset), dt).toMinutes()
                if (mins >= 0) "${mins} min → $dest${if (rmk.isNotBlank()) " ($rmk)" else ""}"
                else null
            }.getOrNull()
        }
        return if (lines.isEmpty()) "No upcoming buses." else lines.joinToString("\n")
    }

    /**
     * Convert raw PCM bytes recorded from the microphone into a standard WAV byte array that can be
     * sent to the Whisper API.
     */
    private fun buildWav(pcm: ByteArray, format: AudioFormat): ByteArray {
        val rate = format.sampleRateHz ?: 16_000
        val ch = format.channelCount ?: 1
        val bps = 16
        val byteRate = rate * ch * bps / 8
        val blockAlign = ch * bps / 8
        val buf = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + pcm.size)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(ch.toShort())
        buf.putInt(rate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bps.toShort())
        buf.put("data".toByteArray())
        buf.putInt(pcm.size)
        buf.put(pcm)
        return buf.array()
    }

    // ───────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // ───────────────────────────────────────────────────────────────────────

    companion object {
        private const val KEY_DISTRICT = "district"
        private const val KEY_USER_LAT = "user_lat"
        private const val KEY_USER_LNG = "user_lng"

        private const val HKO_CURRENT_URL =
            "https://data.weather.gov.hk/weatherAPI/opendata/weather.php?dataType=rhrread&lang=en"
        private const val HKO_FORECAST_URL =
            "https://data.weather.gov.hk/weatherAPI/opendata/weather.php?dataType=flw&lang=en"

        private const val KMB_BASE = "https://data.etabus.gov.hk/v1/transport/kmb"
        private const val KMB_ROUTES_URL = "$KMB_BASE/route/"
        private const val HK_ETA_DB_URL = "https://data.hkbus.app/routeFareList.min.json"
        private const val HK_ETA_DB_FALLBACK_URL =
            "https://hkbus.github.io/hk-bus-crawling/routeFareList.min.json"
        private const val TAG = "HkRouterPlanner"
        private const val WALKING_RADIUS_M = 500.0

        /**
         * Coordinates of HKO automatic weather stations, keyed by the exact place name
         * returned in the rhrread API response. Used for GPS-based nearest-station matching.
         */
        private val HKO_STATION_COORDS = mapOf(
            "King's Park" to LatLng(22.3117, 114.1733),
            "Hong Kong Observatory" to LatLng(22.3021, 114.1742),
            "Hong Kong Park" to LatLng(22.2783, 114.1617),
            "Happy Valley" to LatLng(22.2712, 114.1839),
            "Wong Chuk Hang" to LatLng(22.2483, 114.1741),
            "Stanley" to LatLng(22.2183, 114.2133),
            "Kai Tak Runway Park" to LatLng(22.2983, 114.2183),
            "Kwun Tong" to LatLng(22.3123, 114.2256),
            "Tseung Kwan O" to LatLng(22.3167, 114.2667),
            "Sai Kung" to LatLng(22.3814, 114.2705),
            "Kau Sai Chau" to LatLng(22.3683, 114.3050),
            "Pak Tam Chung" to LatLng(22.4083, 114.3267),
            "Waglan Island" to LatLng(22.1833, 114.3000),
            "Tate's Cairn" to LatLng(22.3567, 114.2183),
            "Wong Tai Sin" to LatLng(22.3420, 114.1937),
            "Sham Shui Po" to LatLng(22.3301, 114.1595),
            "Tsing Yi" to LatLng(22.3467, 114.1050),
            "Tsuen Wan Ho Koon" to LatLng(22.3814, 114.1097),
            "Tsuen Wan Shing Mun Valley" to LatLng(22.3767, 114.1317),
            "Sha Tin" to LatLng(22.3870, 114.1950),
            "Tai Po" to LatLng(22.4500, 114.1700),
            "Tai Mei Tuk" to LatLng(22.4733, 114.2267),
            "Shek Kong" to LatLng(22.4367, 114.0833),
            "Wetland Park" to LatLng(22.4650, 114.0050),
            "Yuen Long Park" to LatLng(22.4456, 114.0222),
            "Lau Fau Shan" to LatLng(22.4717, 113.9833),
            "Tuen Mun" to LatLng(22.3911, 113.9771),
            "Chek Lap Kok" to LatLng(22.3089, 113.9197),
            "Cheung Chau" to LatLng(22.2100, 114.0267),
            "Ta Kwu Ling" to LatLng(22.5267, 114.1533),
            "North District" to LatLng(22.4947, 114.1381),
        )

        private val HK_DISTRICT_CENTERS = mapOf(
            "Central and Western District" to LatLng(22.2855, 114.1577),
            "Wan Chai" to LatLng(22.2770, 114.1750),
            "Eastern District" to LatLng(22.2841, 114.2241),
            "Southern District" to LatLng(22.2473, 114.1600),
            "Yau Tsim Mong" to LatLng(22.3193, 114.1694),
            "Sham Shui Po" to LatLng(22.3301, 114.1595),
            "Kowloon City" to LatLng(22.3282, 114.1916),
            "Wong Tai Sin" to LatLng(22.3420, 114.1937),
            "Kwun Tong" to LatLng(22.3123, 114.2256),
            "Kwai Tsing" to LatLng(22.3600, 114.1250),
            "Tsuen Wan" to LatLng(22.3707, 114.1146),
            "Tuen Mun" to LatLng(22.3911, 113.9771),
            "Yuen Long" to LatLng(22.4456, 114.0222),
            "North District" to LatLng(22.4947, 114.1381),
            "Tai Po" to LatLng(22.4500, 114.1700),
            "Sha Tin" to LatLng(22.3870, 114.1950),
            "Sai Kung" to LatLng(22.3814, 114.2705),
            "Islands District" to LatLng(22.2611, 113.9461),
        )
    }
}
