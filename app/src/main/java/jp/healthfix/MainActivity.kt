package jp.healthfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class Segment(val start: LocalDateTime, val end: LocalDateTime, val count: Long)
data class DayEntry(val day: String, val total: Int, val segments: List<Segment>, val method: String)

class MainActivity : ComponentActivity() {
    private val permissions = setOf(HealthPermission.getWritePermission(StepsRecord::class), HealthPermission.getReadPermission(StepsRecord::class))
    private var client: HealthConnectClient? = null
    private var granted by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var history by mutableStateOf(listOf<DayEntry>())
    private var tab by mutableStateOf(0)
    private var minSteps by mutableIntStateOf(6000)
    private var maxSteps by mutableIntStateOf(9000)
    private var startText by mutableStateOf("08:00")
    private var endText by mutableStateOf("20:00")
    private var interval by mutableIntStateOf(15)
    private var autoRun by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { result -> granted = result.containsAll(permissions); message = if (granted) "Health Connect の権限を許可しました" else "歩数の読み書き権限が必要です" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = loadHistory()
        val sdk = HealthConnectClient.getSdkStatus(this)
        if (sdk == HealthConnectClient.SDK_AVAILABLE) { client = HealthConnectClient.getOrCreate(this); lifecycleScope.launch { granted = client!!.permissionController.getGrantedPermissions().containsAll(permissions) } }
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B), secondary = androidx.compose.ui.graphics.Color(0xFF83B8A6))) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().padding(20.dp)) { Text("health-fix", style = MaterialTheme.typography.headlineMedium); Text("歩数データを自分で管理", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(18.dp)); TabRow(selectedTabIndex = tab) { Tab(tab == 0, { tab = 0 }, text = { Text("ホーム") }); Tab(tab == 1, { tab = 1 }, text = { Text("履歴") }); Tab(tab == 2, { tab = 2 }, text = { Text("設定") }) }; Spacer(Modifier.height(16.dp)); when(tab) { 0 -> HomeScreen(); 1 -> HistoryScreen(); else -> SettingsScreen() } } } } }
    }

    @Composable private fun HomeScreen() {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text("今日の状態", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(if(history.any { it.day == LocalDate.now().toString() }) "今日の歩数を書き込み済み" else "未実行"); Text("設定した時間帯に歩数を分割して記録します。") } }
        Spacer(Modifier.height(18.dp)); Button(onClick = { writeToday() }, modifier = Modifier.fillMaxWidth(), enabled = client != null) { Text("今日の分を今すぐ実行") }; Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = { permissionLauncher.launch(permissions) }, modifier = Modifier.fillMaxWidth(), enabled = client != null) { Text(if(granted) "権限を確認（許可済み）" else "Health Connect の権限を確認") }; Spacer(Modifier.height(10.dp)); Text("他のアプリの歩数との合算は、Health Connect のデータソース優先順位に従います。二重計上になる場合があります。", style = MaterialTheme.typography.bodySmall); if(message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message, color = MaterialTheme.colorScheme.primary) }
    }
    @Composable private fun HistoryScreen() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { createForDate() }) { Text("＋ 日付を選んで作成") } }
        if(history.isEmpty()) Text("まだ記録はありません") else LazyColumn { items(history.sortedByDescending { it.day }) { entry -> Card(Modifier.fillMaxWidth().padding(bottom=8.dp)) { Column(Modifier.padding(14.dp)) { Text(entry.day, style=MaterialTheme.typography.titleMedium); Text("${entry.total} 歩 · ${entry.segments.firstOrNull()?.start?.toLocalTime()}–${entry.segments.lastOrNull()?.end?.toLocalTime()} · ${entry.method}"); entry.segments.forEach { Text("${it.start.toLocalTime()}–${it.end.toLocalTime()}　${it.count}歩", style=MaterialTheme.typography.bodySmall) } } } } }
    }
    @Composable private fun SettingsScreen() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("自動反映"); Switch(autoRun, { autoRun = it; savePrefs() }) }
        NumberField("最小歩数", minSteps) { minSteps = it.coerceIn(1,100000); if(maxSteps < minSteps) maxSteps=minSteps; savePrefs() }
        NumberField("最大歩数", maxSteps) { maxSteps = it.coerceIn(minSteps,100000); savePrefs() }
        TextField(startText, { startText=it.take(5); savePrefs() }, label={Text("開始時刻 (HH:mm)")}, modifier=Modifier.fillMaxWidth())
        TextField(endText, { endText=it.take(5); savePrefs() }, label={Text("終了時刻 (HH:mm)")}, modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(15,30,60).forEach { FilterChip(interval==it, { interval=it; savePrefs() }, label={Text("${it}分")}) } }
        val cap = runCatching { maxCapacity(startText, endText, interval) }.getOrDefault(0)
        Text("1日の上限: ${cap}歩（180歩/分）", style=MaterialTheme.typography.bodySmall)
        if(maxSteps > cap) Text("最大歩数が時間帯の上限を超えています", color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp)); Text("曜日: 毎日", style=MaterialTheme.typography.bodyMedium); Text("補完: OFF（このバージョンでは未対応）", style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp)); Text("記録は Health Connect に手動入力として保存します。アプリをアンインストールすると履歴は消えます。", style=MaterialTheme.typography.bodySmall)
    }
    @Composable private fun NumberField(label:String, value:Int, onValue:(Int)->Unit) { TextField(value.toString(), { it.toIntOrNull()?.let(onValue) }, label={Text(label)}, modifier=Modifier.fillMaxWidth()) }

    private fun writeToday() { writeDate(LocalDate.now(), "手動") }
    private fun createForDate() {
        val picker = android.app.DatePickerDialog(this, { _, y,m,d -> writeDate(LocalDate.of(y,m+1,d), "手動") }, LocalDate.now().year, LocalDate.now().monthValue-1, LocalDate.now().dayOfMonth)
        picker.datePicker.maxDate = System.currentTimeMillis(); picker.show()
    }
    private fun writeDate(day:LocalDate, method:String) {
        if(!granted) { permissionLauncher.launch(permissions); return }
        if(history.any { it.day == day.toString() }) { message = "${day} は書き込み済みのためスキップしました"; return }
        val begin=runCatching { LocalTime.parse(startText) }.getOrElse { message="開始時刻を HH:mm 形式で入力してください"; return }
        val finish=runCatching { LocalTime.parse(endText) }.getOrElse { message="終了時刻を HH:mm 形式で入力してください"; return }
        val cap=runCatching { maxCapacity(startText,endText,interval) }.getOrDefault(0)
        if(!finish.isAfter(begin) || maxSteps > cap || minSteps > maxSteps) { message="時刻または歩数の設定を確認してください（上限 ${cap} 歩）"; return }
        val total=Random.nextInt(minSteps,maxSteps+1)
        val parts=generate(total,day,begin,finish,interval)
        lifecycleScope.launch {
            try {
                val zone=ZoneId.systemDefault(); val client0=client ?: return@launch
                val records=parts.filter { it.count>0 }.mapIndexed { i,s -> StepsRecord(count=s.count,startTime=s.start.atZone(zone).toInstant(),startZoneOffset=zone.rules.getOffset(s.start.atZone(zone).toInstant()),endTime=s.end.atZone(zone).toInstant(),endZoneOffset=zone.rules.getOffset(s.end.atZone(zone).toInstant()),metadata=Metadata.manualEntry(clientRecordId="healthfix-${day.format(DateTimeFormatter.BASIC_ISO_DATE)}-${s.start.format(DateTimeFormatter.ofPattern("HHmm"))}")) }
                client0.insertRecords(records)
                history=(history+DayEntry(day.toString(),total,parts,method)).sortedByDescending { it.day }; saveHistory(); message="${total} 歩を Health Connect に書き込みました"
            } catch(e:Exception) { message="書き込みに失敗しました: ${e.localizedMessage ?: "Health Connect を確認してください"}" }
        }
    }
    private fun generate(total:Int,day:LocalDate,begin:LocalTime,finish:LocalTime,step:Int):List<Segment> {
        val chunks=mutableListOf<Pair<LocalDateTime,LocalDateTime>>(); var t=begin
        while(t<finish) { val next=minOf(t.plusMinutes(step.toLong()),finish); chunks+=day.atTime(t) to day.atTime(next); t=next }
        val capacities=chunks.map { java.time.Duration.between(it.first,it.second).toMinutes().toInt()*180 }
        val out=MutableList(chunks.size){0L}; var remaining=total
        val active=chunks.indices.filter { Random.nextFloat()>=0.2f }.ifEmpty { listOf(Random.nextInt(chunks.size)) }.toMutableList()
        while(remaining>0 && active.isNotEmpty()) { val weights=active.map { Random.nextDouble(0.2,1.0) }; val sum=weights.sum(); var used=0
            active.toList().forEachIndexed { ix, idx -> val amount=if(ix==active.lastIndex) remaining-used else (remaining*weights[ix]/sum).toInt().coerceAtLeast(1); val n=minOf(amount.toLong(),capacities[idx].toLong()-out[idx]); if(n>0){out[idx]+=n;used+=n.toInt()} }
            remaining-=used; active.removeAll { out[it]>=capacities[it] }; if(used==0 && active.isNotEmpty()) { val i=active.first(); out[i]++; remaining--; if(out[i]>=capacities[i]) active.remove(i) }
        }
        if(remaining>0) { message="この設定では上限を超えています" }
        return chunks.mapIndexed { i,p -> Segment(p.first,p.second,out[i]) }
    }
    private fun maxCapacity(start:String,end:String,step:Int):Int { val a=LocalTime.parse(start); val b=LocalTime.parse(end); if(!b.isAfter(a)) return 0; var t=a; var result=0; while(t<b){ val n=minOf(t.plusMinutes(step.toLong()),b); result+=java.time.Duration.between(t,n).toMinutes().toInt()*180; t=n }; return result }
    private fun savePrefs(){ getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("min",minSteps).putInt("max",maxSteps).putString("start",startText).putString("end",endText).putInt("interval",interval).putBoolean("auto",autoRun).apply() }
    private fun saveHistory(){ val arr=JSONArray(); history.forEach { e -> val obj=JSONObject().put("day",e.day).put("total",e.total).put("method",e.method); val seg=JSONArray(); e.segments.forEach { seg.put(JSONObject().put("start",it.start.toString()).put("end",it.end.toString()).put("count",it.count)) }; obj.put("segments",seg); arr.put(obj) }; getSharedPreferences("history",MODE_PRIVATE).edit().putString("days",arr.toString()).apply() }
    private fun loadHistory():List<DayEntry>{ minSteps=getSharedPreferences("settings",MODE_PRIVATE).getInt("min",6000); maxSteps=getSharedPreferences("settings",MODE_PRIVATE).getInt("max",9000); startText=getSharedPreferences("settings",MODE_PRIVATE).getString("start","08:00")!!; endText=getSharedPreferences("settings",MODE_PRIVATE).getString("end","20:00")!!; interval=getSharedPreferences("settings",MODE_PRIVATE).getInt("interval",15); autoRun=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("auto",false); val a=runCatching { JSONArray(getSharedPreferences("history",MODE_PRIVATE).getString("days","[]")) }.getOrNull()?:return emptyList(); return (0 until a.length()).map { i -> val o=a.getJSONObject(i); val s=o.getJSONArray("segments"); DayEntry(o.getString("day"),o.getInt("total"),(0 until s.length()).map { j -> val x=s.getJSONObject(j); Segment(LocalDateTime.parse(x.getString("start")),LocalDateTime.parse(x.getString("end")),x.getLong("count")) },o.getString("method")) } }
}
