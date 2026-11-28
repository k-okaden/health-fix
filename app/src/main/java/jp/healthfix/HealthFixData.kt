package jp.healthfix

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class StepSettings(
    val minSteps: Int = 48_000,
    val maxSteps: Int = 52_000,
    val start: LocalTime = LocalTime.MIDNIGHT,
    val end: LocalTime = LocalTime.of(8, 0),
    val intervalMinutes: Int = 15,
    val autoRun: Boolean = true,
)

data class Segment(val start: LocalDateTime, val end: LocalDateTime, val count: Long)
data class DayEntry(val day: String, val total: Int, val segments: List<Segment>, val method: String)

object HealthFixData {
    private const val SETTINGS = "settings"
    private const val HISTORY = "history"
    private val dateId = DateTimeFormatter.BASIC_ISO_DATE
    private val timeId = DateTimeFormatter.ofPattern("HHmm")

    fun settings(context: Context): StepSettings {
        val p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (!p.getBoolean("migrated_v3", false)) {
            val oldDefaults = p.getInt("min", 6_000) == 6_000 && p.getInt("max", 9_000) == 9_000 &&
                p.getString("start", "08:00") == "08:00" && p.getString("end", "20:00") == "20:00" && !p.getBoolean("auto", false)
            if (oldDefaults) p.edit().putInt("min", 48_000).putInt("max", 52_000).putString("start", "00:00").putString("end", "08:00").putBoolean("auto", true).apply()
            p.edit().putBoolean("migrated_v3", true).apply()
        }
        return StepSettings(
            minSteps = p.getInt("min", 48_000), maxSteps = p.getInt("max", 52_000),
            start = parseTime(p.getString("start", "00:00"), LocalTime.MIDNIGHT),
            end = parseTime(p.getString("end", "08:00"), LocalTime.of(8, 0)),
            intervalMinutes = p.getInt("interval", 15).let { if (it in listOf(15, 30, 60)) it else 15 },
            autoRun = p.getBoolean("auto", true),
        )
    }

    fun saveSettings(context: Context, value: StepSettings) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putInt("min", value.minSteps).putInt("max", value.maxSteps)
            .putString("start", value.start.toString()).putString("end", value.end.toString())
            .putInt("interval", value.intervalMinutes).putBoolean("auto", value.autoRun).apply()
    }

    fun history(context: Context): List<DayEntry> {
        val raw = context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString("days", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val segments = o.getJSONArray("segments")
                DayEntry(o.getString("day"), o.getInt("total"), (0 until segments.length()).map { j ->
                    val s = segments.getJSONObject(j)
                    Segment(LocalDateTime.parse(s.getString("start")), LocalDateTime.parse(s.getString("end")), s.getLong("count"))
                }, o.optString("method", "手動"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveEntry(context: Context, entry: DayEntry) {
        val all = history(context).filterNot { it.day == entry.day } + entry
        val array = JSONArray()
        all.forEach { e ->
            val segments = JSONArray()
            e.segments.forEach { s -> segments.put(JSONObject().put("start", s.start.toString()).put("end", s.end.toString()).put("count", s.count)) }
            array.put(JSONObject().put("day", e.day).put("total", e.total).put("method", e.method).put("segments", segments))
        }
        context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit().putString("days", array.toString()).apply()
    }

    fun maxCapacity(settings: StepSettings): Int {
        if (!settings.end.isAfter(settings.start)) return 0
        var time = settings.start
        var capacity = 0
        while (time < settings.end) {
            val next = minOf(time.plusMinutes(settings.intervalMinutes.toLong()), settings.end)
            capacity += Duration.between(time, next).toMinutes().toInt() * 180
            time = next
        }
        return capacity
    }

    fun generate(total: Int, day: LocalDate, settings: StepSettings): List<Segment> {
        val chunks = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var time = settings.start
        while (time < settings.end) {
            val next = minOf(time.plusMinutes(settings.intervalMinutes.toLong()), settings.end)
            chunks += day.atTime(time) to day.atTime(next)
            time = next
        }
        val capacities = chunks.map { Duration.between(it.first, it.second).toMinutes().toInt() * 180 }
        val counts = MutableList(chunks.size) { 0L }
        var remaining = total
        val active = chunks.indices.filter { Random.nextFloat() >= 0.2f }.ifEmpty { listOf(Random.nextInt(chunks.size)) }.toMutableList()
        val inactive = chunks.indices.filterNot { it in active }.shuffled().toMutableList()
        while (active.sumOf { capacities[it] } < total && inactive.isNotEmpty()) active += inactive.removeAt(0)
        while (remaining > 0 && active.isNotEmpty()) {
            val weights = active.map { Random.nextDouble(0.2, 1.0) }
            val weightTotal = weights.sum()
            var assigned = 0
            active.toList().forEachIndexed { index, slot ->
                val share = if (index == active.lastIndex) remaining - assigned else (remaining * weights[index] / weightTotal).toInt().coerceAtLeast(1)
                val add = minOf(share.toLong(), capacities[slot].toLong() - counts[slot])
                if (add > 0) { counts[slot] += add; assigned += add.toInt() }
            }
            remaining -= assigned
            active.removeAll { counts[it] >= capacities[it] }
            if (assigned == 0 && active.isNotEmpty()) {
                val slot = active.first(); counts[slot]++; remaining--
                if (counts[slot] >= capacities[slot]) active.remove(slot)
            }
        }
        return chunks.mapIndexed { i, pair -> Segment(pair.first, pair.second, counts[i]) }
    }

    suspend fun writeDay(context: Context, day: LocalDate, settings: StepSettings, method: String): DayEntry = withContext(Dispatchers.IO) {
        val existing = history(context).firstOrNull { it.day == day.toString() }
        check(existing == null) { "${day} は書き込み済みです" }
        require(settings.end.isAfter(settings.start)) { "終了時刻は開始時刻より後にしてください" }
        require(settings.minSteps in 1..settings.maxSteps && settings.maxSteps <= 100_000) { "歩数の範囲を確認してください" }
        require(settings.maxSteps <= maxCapacity(settings)) { "時間帯の上限（${maxCapacity(settings)}歩）を超えています" }
        val client = HealthConnectClient.getOrCreate(context)
        val permissions = setOf(HealthPermission.getWritePermission(StepsRecord::class), HealthPermission.getReadPermission(StepsRecord::class))
        check(client.permissionController.getGrantedPermissions().containsAll(permissions)) { "Health Connect の歩数読み書き権限が必要です" }
        val total = Random.nextInt(settings.minSteps, settings.maxSteps + 1)
        val segments = generate(total, day, settings)
        val zone = ZoneId.systemDefault()
        val records = segments.filter { it.count > 0 }.map { s ->
            val start = s.start.atZone(zone)
            val end = s.end.atZone(zone)
            StepsRecord(count = s.count, startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = end.toInstant(), endZoneOffset = end.offset,
                metadata = Metadata.manualEntry(clientRecordId = "healthfix-${day.format(dateId)}-${s.start.format(timeId)}"))
        }
        client.insertRecords(records)
        DayEntry(day.toString(), total, segments, method).also { saveEntry(context, it) }
    }

    private fun parseTime(value: String?, fallback: LocalTime) = runCatching { LocalTime.parse(value) }.getOrDefault(fallback)
}
