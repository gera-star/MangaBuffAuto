package com.example.myapplication.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.automation.TradeAutomation
import com.example.myapplication.automation.TradeLogEntry
import com.example.myapplication.automation.TradeRuleMode
import com.example.myapplication.data.MangaBuffAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TradeBackground = Color(0xFF101218)
private val TradeSurface = Color(0xFF1B202A)
private val TradeAccent = Color(0xFFB45CFF)
private val TradeCyan = Color(0xFF62E5FF)
private const val TRADE_WEB_CARDS_URL = "https://mangabuff.ru/users/1539681/cards"

/**
 * Trading UI is an overlay above the existing app UI. The normal screen stays
 * composed underneath so the existing account/session and WebView logic is not reset.
 */
@Composable
fun TradeModeScreen(
    viewModel: MainViewModel,
    engine: TradeAutomation,
    onBack: () -> Unit
) {
    BackHandler(enabled = true, onBack = onBack)
    val accounts by viewModel.accounts.collectAsState()
    val isRunning by engine.isRunning.collectAsState()
    val waitingForExecution by engine.waitingForExecution.collectAsState()
    val activeAccountId by engine.activeAccountId.collectAsState()
    val logEntries by engine.logs.collectAsState()
    val context = LocalContext.current

    var selectedAccountId by remember {
        mutableStateOf(
            engine.activeAccountId.value
                ?: accounts.firstOrNull { it.getSafeCookiesJson().isNotBlank() }?.id
                .orEmpty()
        )
    }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var ruleMode by remember { mutableStateOf(engine.savedMode(selectedAccountId)) }
    var coefficientText by remember { mutableStateOf(engine.savedProfitCoefficient(selectedAccountId)) }
    var autoAcceptEnabled by remember { mutableStateOf(engine.savedAutoAcceptEnabled(selectedAccountId)) }
    var specialWeightsText by remember { mutableStateOf(engine.savedSpecialInstanceWeights(selectedAccountId)) }

    LaunchedEffect(activeAccountId, accounts) {
        if (!activeAccountId.isNullOrBlank()) {
            selectedAccountId = activeAccountId.orEmpty()
        } else if (selectedAccountId.isBlank() || accounts.none { it.id == selectedAccountId }) {
            selectedAccountId = accounts.firstOrNull { it.getSafeCookiesJson().isNotBlank() }?.id.orEmpty()
        }
    }

    LaunchedEffect(selectedAccountId) {
        engine.selectLogAccount(selectedAccountId)
        if (selectedAccountId.isNotBlank()) {
            ruleMode = engine.savedMode(selectedAccountId)
            coefficientText = engine.savedProfitCoefficient(selectedAccountId)
            autoAcceptEnabled = engine.savedAutoAcceptEnabled(selectedAccountId)
            specialWeightsText = engine.savedSpecialInstanceWeights(selectedAccountId)
        }
    }

    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    val coefficient = coefficientText.toDoubleOrNull()?.coerceIn(1.0, 10.0) ?: 1.5
    val hasSession = selectedAccount?.getSafeCookiesJson()?.isNotBlank() == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TradeBackground)
            // Respect Android status/shade area, cutouts, navigation bar, and keyboard.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 46.dp, top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "АВТООБМЕН",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "MangaBuff · отдельный режим",
                        color = Color(0xFFABB4C6),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    color = if (isRunning) Color(0xFF174D3B) else Color(0xFF36313D),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        when {
                            !isRunning -> "ОСТАНОВЛЕН"
                            waitingForExecution -> "ОЖИДАНИЕ"
                            autoAcceptEnabled -> "АВТОПРИЁМ"
                            else -> "АНАЛИЗ"
                        },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        color = when {
                            !isRunning -> Color(0xFFD0C7D8)
                            waitingForExecution -> Color(0xFF62E5FF)
                            autoAcceptEnabled -> Color(0xFFFFC27D)
                            else -> Color(0xFF7DF0B1)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TradeSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("Аккаунт", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Box {
                        OutlinedButton(
                            onClick = { if (!isRunning) accountMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRunning
                        ) {
                            Text(
                                selectedAccount?.username?.ifBlank { "Аккаунт без имени" }
                                    ?: "Выбери аккаунт",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(account.username.ifBlank { "Аккаунт" })
                                            Text(
                                                if (account.getSafeCookiesJson().isNotBlank()) "Сохранённая сессия есть"
                                                else "Нет сохранённой сессии",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    enabled = account.getSafeCookiesJson().isNotBlank(),
                                    onClick = {
                                        selectedAccountId = account.id
                                        accountMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        if (hasSession) "Используется сохранённая авторизация аккаунта."
                        else "У выбранного аккаунта нет Cookie. Добавь/авторизуй его в обычном меню.",
                        color = if (hasSession) Color(0xFF88DDB7) else Color(0xFFFFB39E),
                        fontSize = 11.sp
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TradeSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Мониторинг", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("Опрос каждые 45 секунд", color = Color(0xFFABB4C6), fontSize = 10.sp)
                        }
                        Switch(checked = isRunning, enabled = isRunning || (selectedAccount != null && hasSession),
                            onCheckedChange = { enabled ->
                                if (enabled && selectedAccount != null) engine.start(selectedAccount, ruleMode, coefficient) else engine.stop()
                            })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Автопринятие", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(if (autoAcceptEnabled) "Подходящие предложения будут приниматься автоматически."
                                else "Безопасный режим: только анализ, без POST принятия.",
                                color = if (autoAcceptEnabled) Color(0xFFFFC27D) else Color(0xFFABB4C6), fontSize = 11.sp)
                        }
                        Switch(checked = autoAcceptEnabled, onCheckedChange = { enabled ->
                            autoAcceptEnabled = enabled; engine.setAutoAcceptEnabled(selectedAccountId, enabled)
                        })
                    }
                    HorizontalDivider(color = Color(0xFF343A47))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Защита карт", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TRADE_WEB_CARDS_URL)))
                                }
                            },
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("WEB", fontSize = 11.sp)
                        }
                    }
                    Text(
                        "Открой свои карты на MangaBuff и включи сайтный замок у экземпляров, которые нельзя использовать для запросов на обмен.",
                        color = Color(0xFFBBC3D2),
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                    OutlinedTextField(
                        value = specialWeightsText,
                        onValueChange = { specialWeightsText = it.take(8000); engine.saveSpecialInstanceWeights(selectedAccountId, specialWeightsText) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Веса спец. карт") },
                        placeholder = { Text("card_user_id=вес, например 690490862=6") },
                        supportingText = { Text("H/N/V/Q/L/K/W: вес 1–8 для каждого экземпляра; X отдавать нельзя.") },
                        minLines = 1, maxLines = 2,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TradeSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Правило оценки", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (ruleMode == TradeRuleMode.STRICT) {
                            Button(
                                onClick = { ruleMode = TradeRuleMode.STRICT; engine.saveMode(selectedAccountId, ruleMode) },
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Строгий", fontSize = 12.sp) }
                        } else {
                            OutlinedButton(
                                onClick = { ruleMode = TradeRuleMode.STRICT; engine.saveMode(selectedAccountId, ruleMode) },
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Строгий", fontSize = 12.sp) }
                        }
                        if (ruleMode == TradeRuleMode.VALUE) {
                            Button(
                                onClick = { ruleMode = TradeRuleMode.VALUE; engine.saveMode(selectedAccountId, ruleMode) },
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("По сумме", fontSize = 12.sp) }
                        } else {
                            OutlinedButton(
                                onClick = { ruleMode = TradeRuleMode.VALUE; engine.saveMode(selectedAccountId, ruleMode) },
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("По сумме", fontSize = 12.sp) }
                        }
                    }
                    if (ruleMode == TradeRuleMode.STRICT) {
                        Text(
                            "Каждая получаемая обычная карта должна быть не ниже ранга отдаваемой.",
                            color = Color(0xFFBBC3D2),
                            fontSize = 11.sp
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Коэффициент выгоды", color = Color(0xFFBBC3D2), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = coefficientText,
                                onValueChange = {
                                    coefficientText = it.filter { char -> char.isDigit() || char == '.' || char == ',' }.replace(',', '.').take(4)
                                    engine.saveProfitCoefficient(selectedAccountId, coefficientText)
                                },
                                modifier = Modifier.width(92.dp),
                                enabled = !isRunning,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                label = { Text("×", fontSize = 10.sp)
                                }
                            )
                        }
                        Text(
                            "Сумма весов получаемых карт должна быть не меньше веса отдаваемой × коэффициент. Специальным экземплярам нужен явно заданный вес; X при отдаче запрещён.",
                            color = Color(0xFFBBC3D2),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    if (isRunning && activeAccountId != selectedAccountId) {
                        Text("Автообмен работает для другого выбранного аккаунта. Останови его, чтобы сменить аккаунт.", color = Color(0xFFFFC27D), fontSize = 11.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Журнал событий", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${logEntries.size}", color = Color(0xFFABB4C6), fontSize = 11.sp)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (logEntries.isEmpty()) {
                    item {
                        Text(
                            "Здесь появится результат проверки предложений.",
                            color = Color(0xFF8B94A5),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(logEntries.asReversed()) { entry ->
                    TradeLogRow(entry)
                }
            }

            Text(
                "Ранг берётся из HTML каталога. Если ранг или замок нельзя определить уверенно, обмен пропускается.",
                color = Color(0xFF737D90),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }

        TradeModeEdgeButton(
            modifier = Modifier.align(Alignment.CenterEnd),
            isBack = true,
            onClick = onBack
        )
    }
}

@Composable
private fun TradeLogRow(entry: TradeLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF191E27), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            formatter.format(Date(entry.time)),
            color = Color(0xFF7E8A9F),
            fontSize = 10.sp,
            modifier = Modifier.width(54.dp)
        )
        Text(
            entry.message,
            color = if (entry.isError) Color(0xFFFFA99B) else Color(0xFFD5DCE8),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun TradeModeEdgeButton(
    modifier: Modifier = Modifier,
    isBack: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .width(34.dp)
            .height(74.dp)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(TradeCyan, TradeAccent)),
                shape = RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp)
            ),
        color = Color(0xFF21182E),
        shape = RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp),
        shadowElevation = 9.dp,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (isBack) "‹" else "⇆",
                color = if (isBack) TradeCyan else TradeAccent,
                fontSize = if (isBack) 31.sp else 23.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
