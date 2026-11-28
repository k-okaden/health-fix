package jp.healthfix

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val permissions = setOf(HealthPermission.getWritePermission(StepsRecord::class), HealthPermission.getReadPermission(StepsRecord::class))
    private var client: HealthConnectClient? = null
    private var granted by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var history by mutableStateOf(listOf<DayEntry>())
    private var tab by mutableIntStateOf(0)
    private var settings by mutableStateOf(StepSettings())
    private var busy by mutableStateOf(false)
    private var fromDate by mutableStateOf(LocalDate.now())
    private var toDate by mutableStateOf(LocalDate.now())

    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result.containsAll(permissions)
        message = if (granted) "Health Connect の権限を許可しました" else "歩数の読み書き権限が必要です"
        if (granted) AutoSchedule.schedule(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = HealthFixData.settings(this)
        history = HealthFixData.history(this)
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            client = HealthConnectClient.getOrCreate(this)
            lifecycleScope.launch { granted = client!!.permissionController.getGrantedPermissions().containsAll(permissions) }
        }
        AutoSchedule.schedule(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B), secondary = androidx.compose.ui.graphics.Color(0xFF83B8A6))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("health-fix", style = MaterialTheme.typography.headlineMedium)
                        Text("毎日 ${settings.start}〜${settings.end} に約5万歩を自動分散", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(18.dp))
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("ホーム") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("履歴・一括作成") })
                            Tab(tab == 2, { tab = 2 }, text = { Text("設定") })
                        }
                        Spacer(Modifier.height(16.dp))
                        when (tab) { 0 -> HomeScreen(); 1 -> HistoryScreen(); else -> SettingsScreen() }
                    }
                }
            }
        }
    }

    @Composable private fun HomeScreen() {
        val today = LocalDate.now().toString()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("今日の状態", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(if (history.any { it.day == today }) "今日の歩数を書き込み済み" else if (settings.autoRun) "毎日 ${settings.end.format(DateTimeFormatter.ofPattern("HH:mm"))} 過ぎに自動実行予定" else "自動実行はOFFです")
                Text("設定: ${settings.minSteps}〜${settings.maxSteps}歩 / ${settings.start}〜${settings.end}")
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { writeDate(LocalDate.now(), "手動") }, modifier = Modifier.fillMaxWidth(), enabled = client != null && !busy) { Text("今日の分を今すぐ実行") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { permissionLauncher.launch(permissions) }, modifier = Modifier.fillMaxWidth(), enabled = client != null) { Text(if (granted) "Health Connect 権限を確認（許可済み）" else "Health Connect の権限を確認") }
        Spacer(Modifier.height(10.dp))
        Text("他アプリの歩数との合算は Health Connect のデータソース優先順位に従います。二重計上になる場合があります。", style = MaterialTheme.typography.bodySmall)
        if (client == null) Text("Health Connect が利用できません。Android 13以下ではHealth Connectアプリをインストールしてください。", color = MaterialTheme.colorScheme.error)
        if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message, color = MaterialTheme.colorScheme.primary) }
    }

    @Composable private fun HistoryScreen() {
        Text("期間を指定して未記録の日をまとめて作成", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseDate(fromDate) { fromDate = it } }, modifier = Modifier.weight(1f)) { Text("開始: $fromDate") }
            OutlinedButton(onClick = { chooseDate(toDate) { toDate = it } }, modifier = Modifier.weight(1f)) { Text("終了: $toDate") }
        }
        Button(onClick = { batchCreate() }, modifier = Modifier.fillMaxWidth(), enabled = client != null && !busy && !toDate.isBefore(fromDate) && !toDate.isAfter(LocalDate.now())) {
            Text(if (busy) "一括作成中…" else "この期間を一括作成")
        }
        if (toDate.isAfter(LocalDate.now())) Text("未来の日付は指定できません", color = MaterialTheme.colorScheme.error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        if (history.isEmpty()) Text("まだ記録はありません") else LazyColumn {
            items(history.sortedByDescending { it.day }) { entry ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(entry.day, style = MaterialTheme.typography.titleMedium)
                        Text("${entry.total} 歩 · ${entry.segments.firstOrNull()?.start?.toLocalTime()}–${entry.segments.lastOrNull()?.end?.toLocalTime()} · ${entry.method}")
                        entry.segments.filter { it.count > 0 }.forEach { Text("${it.start.toLocalTime()}–${it.end.toLocalTime()}　${it.count}歩", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    @Composable private fun SettingsScreen() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("毎日の自動実行"); Text("終了時刻の直後に実行（OS都合で遅れる場合があります）", style = MaterialTheme.typography.bodySmall) }
            Switch(settings.autoRun, { updateSettings(settings.copy(autoRun = it)) })
        }
        NumberField("最小歩数", settings.minSteps) { val n = it.coerceIn(1, 100_000); updateSettings(settings.copy(minSteps = n, maxSteps = maxOf(settings.maxSteps, n))) }
        NumberField("最大歩数", settings.maxSteps) { updateSettings(settings.copy(maxSteps = it.coerceIn(settings.minSteps, 100_000))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseTime(settings.start) { updateSettings(settings.copy(start = it)) } }, modifier = Modifier.weight(1f)) { Text("開始 ${settings.start}") }
            OutlinedButton(onClick = { chooseTime(settings.end) { updateSettings(settings.copy(end = it)) } }, modifier = Modifier.weight(1f)) { Text("終了 ${settings.end}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 60).forEach { FilterChip(settings.intervalMinutes == it, { updateSettings(settings.copy(intervalMinutes = it)) }, label = { Text("${it}分") }) } }
        val cap = HealthFixData.maxCapacity(settings)
        Text("時間帯上限: ${cap}歩（180歩/分）", style = MaterialTheme.typography.bodySmall)
        val invalid = !settings.end.isAfter(settings.start) || settings.minSteps > settings.maxSteps || settings.maxSteps > cap
        if (invalid) Text("歩数または時間帯の設定が上限を超えています。保存されません。", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text("約5万歩になるよう初期値を48,000〜52,000歩に設定しています。分割された記録は手動入力としてHealth Connectに保存します。", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }

    @Composable private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
        TextField(value.toString(), { it.toIntOrNull()?.let(onValue) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }

    private fun updateSettings(next: StepSettings) {
        settings = next
        if (next.end.isAfter(next.start) && next.minSteps <= next.maxSteps && next.maxSteps <= HealthFixData.maxCapacity(next)) {
            HealthFixData.saveSettings(this, next)
            AutoSchedule.schedule(this)
        }
    }

    private fun chooseTime(current: LocalTime, selected: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> selected(LocalTime.of(hour, minute)) }, current.hour, current.minute, true).show()
    }

    private fun chooseDate(current: LocalDate, selected: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, year, month, day -> selected(LocalDate.of(year, month + 1, day)) }, current.year, current.monthValue - 1, current.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun writeDate(date: LocalDate, method: String) {
        if (!granted) { permissionLauncher.launch(permissions); return }
        lifecycleScope.launch {
            busy = true
            try {
                HealthFixData.writeDay(this@MainActivity, date, settings, method)
                history = HealthFixData.history(this@MainActivity)
                message = "$date の歩数を書き込みました"
            } catch (e: Exception) { message = e.message ?: "書き込みに失敗しました" }
            finally { busy = false }
        }
    }

    private fun batchCreate() {
        if (!granted) { permissionLauncher.launch(permissions); return }
        if (toDate.isBefore(fromDate) || toDate.isAfter(LocalDate.now())) { message = "日付の範囲を確認してください"; return }
        lifecycleScope.launch {
            busy = true
            var created = 0
            var skipped = 0
            var failed = 0
            var day = fromDate
            while (!day.isAfter(toDate)) {
                if (history.any { it.day == day.toString() }) skipped++
                else try { HealthFixData.writeDay(this@MainActivity, day, settings, "手動"); created++ } catch (_: Exception) { failed++ }
                day = day.plusDays(1)
            }
            history = HealthFixData.history(this@MainActivity)
            message = "一括作成完了: 作成 ${created}日 / 記録済みでスキップ ${skipped}日 / 失敗 ${failed}日"
            busy = false
        }
    }
}
