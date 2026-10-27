package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassremote.data.macro.MacroIds
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TransportType
import com.glassremote.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val STREMIO_KEY = "stremio"

private data class AppEntry(val label: String, val target: String)

private val APPS = listOf(
    AppEntry("YouTube", "https://www.youtube.com"),
    AppEntry("Stremio", STREMIO_KEY),
    AppEntry("Netflix", "market://launch?id=com.netflix.ninja"),
    AppEntry("Prime Video", "market://launch?id=com.amazon.amazonvideo.livingroom"),
    AppEntry("Spotify", "market://launch?id=com.spotify.tv.android"),
    AppEntry("Disney+", "market://launch?id=com.disney.disneyplus"),
)

@Composable
fun RemoteScreen(vm: RemoteViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val transport by vm.activeTransport.collectAsStateWithLifecycle()

    val press: (Int) -> Unit = { vm.key(it) }
    val sendText: (String) -> Unit = { vm.sendText(it) }

    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 wheel, 1 trackpad, 2 keypad
    var showApps by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TopBar(
                name = name,
                status = status,
                transport = transport,
                onStatusTap = { vm.reconnect() },
                onPower = { press(KeyEvent.KEYCODE_POWER) },
            )

            ModeSwitch(mode) { mode = it }

            if (mode == 2) {
                // Keypad View with search input and alphanumeric text transmission
                SearchInputBar(onSend = { sendText(it) })
                NavRow(press)
                Keypad(
                    onCharPress = { sendText(it) },
                    onKeyPress = { press(it) },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillControl(
                        label = "VOL",
                        upIcon = Icons.Rounded.Add,
                        downIcon = Icons.Rounded.Remove,
                        upCode = KeyEvent.KEYCODE_VOLUME_UP,
                        downCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                        press = press,
                    )

                    if (mode == 0) {
                        DpadWheel(press)
                    } else {
                        Trackpad(send = press)
                    }

                    PillControl(
                        label = "CH",
                        upIcon = Icons.Rounded.ExpandLess,
                        downIcon = Icons.Rounded.ExpandMore,
                        upCode = KeyEvent.KEYCODE_CHANNEL_UP,
                        downCode = KeyEvent.KEYCODE_CHANNEL_DOWN,
                        press = press,
                    )
                }

                NavRow(press)
            }

            AppShortcutsRow(
                onAppsClick = { showApps = true },
                onYouTubeClick = { vm.launchApp("https://www.youtube.com") },
                onStremioClick = { vm.openStremio() },
                onHdmi2Click = { vm.runMacro(MacroIds.HDMI2) },
            )
        }

        if (showApps) {
            AppPicker(
                onDismiss = { showApps = false },
                onLaunch = { target ->
                    showApps = false
                    if (target == STREMIO_KEY) vm.openStremio() else vm.launchApp(target)
                },
                onTvList = {
                    showApps = false
                    press(KeyEvent.KEYCODE_ALL_APPS)
                },
            )
        }
    }
}

@Composable
private fun SearchInputBar(onSend: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Type search query or paste text…", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (query.isNotBlank()) {
                    onSend(query)
                    query = ""
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { query = "" }) {
                Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
        IconButton(
            onClick = {
                if (query.isNotBlank()) {
                    onSend(query)
                    query = ""
                }
            },
        ) {
            Icon(Icons.Rounded.Send, contentDescription = "Send text to TV", tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TopBar(
    name: String,
    status: RemoteStatus,
    transport: TransportType,
    onStatusTap: () -> Unit,
    onPower: () -> Unit,
) {
    val phaseDot = when (status.phase) {
        LinkPhase.Ready -> Color(0xFF34D399)
        LinkPhase.Connecting -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }

    val transportLabel = if (transport == TransportType.BluetoothFallback) "BT Fallback" else "Wi-Fi"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onStatusTap)
                .glass(RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(phaseDot, CircleShape))
            Text(
                if (name.isBlank()) "HomeRemote+" else name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                if (transport == TransportType.BluetoothFallback) Icons.Rounded.Bluetooth else Icons.Rounded.Wifi,
                contentDescription = null,
                tint = if (transport == TransportType.BluetoothFallback) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }

        KeyButton(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            onPressed = onPower,
        ) {
            Glyph(Icons.Rounded.PowerSettingsNew, 22.dp, tint = Color(0xFFF87171))
        }
    }
}

@Composable
private fun ModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Wheel", "Trackpad", "Keypad")
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .glass(RoundedCornerShape(22.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun NavRow(press: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        KeyButton(Modifier.size(54.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_BACK) }) {
            Glyph(Icons.Rounded.ArrowBack, 22.dp)
        }
        KeyButton(Modifier.size(54.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_HOME) }) {
            Glyph(Icons.Rounded.Home, 22.dp)
        }
        KeyButton(Modifier.size(54.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_VOLUME_MUTE) }) {
            Glyph(Icons.Rounded.VolumeOff, 22.dp)
        }
    }
}

@Composable
private fun AppShortcutsRow(
    onAppsClick: () -> Unit,
    onYouTubeClick: () -> Unit,
    onStremioClick: () -> Unit,
    onHdmi2Click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppTile("Apps", Icons.Rounded.Apps, onAppsClick)
        AppTile("YouTube", Icons.Rounded.PlayArrow, onYouTubeClick)
        AppTile("Stremio", Icons.Rounded.Movie, onStremioClick)
        AppTile("HDMI 2", Icons.Rounded.Tv, onHdmi2Click)
    }
}

@Composable
private fun DpadWheel(press: (Int) -> Unit) {
    Box(
        Modifier.size(240.dp).glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        KeyButton(Modifier.align(Alignment.TopCenter).size(60.dp), CircleShape, repeat = true, onPressed = { press(KeyEvent.KEYCODE_DPAD_UP) }) {
            Glyph(Icons.Rounded.KeyboardArrowUp, 32.dp)
        }
        KeyButton(Modifier.align(Alignment.BottomCenter).size(60.dp), CircleShape, repeat = true, onPressed = { press(KeyEvent.KEYCODE_DPAD_DOWN) }) {
            Glyph(Icons.Rounded.KeyboardArrowDown, 32.dp)
        }
        KeyButton(Modifier.align(Alignment.CenterStart).size(60.dp), CircleShape, repeat = true, onPressed = { press(KeyEvent.KEYCODE_DPAD_LEFT) }) {
            Glyph(Icons.Rounded.KeyboardArrowLeft, 32.dp)
        }
        KeyButton(Modifier.align(Alignment.CenterEnd).size(60.dp), CircleShape, repeat = true, onPressed = { press(KeyEvent.KEYCODE_DPAD_RIGHT) }) {
            Glyph(Icons.Rounded.KeyboardArrowRight, 32.dp)
        }
        KeyButton(Modifier.size(80.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_DPAD_CENTER) }) {
            Text("OK", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Trackpad(send: (Int) -> Unit) {
    Box(
        Modifier
            .size(240.dp)
            .glass(RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                val threshold = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var ax = 0f
                    var ay = 0f
                    var moved = false
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            finished = true
                        } else {
                            val d = change.positionChange()
                            ax += d.x
                            ay += d.y
                            if (abs(ax) > threshold || abs(ay) > threshold) {
                                moved = true
                                val code = if (abs(ax) > abs(ay)) {
                                    if (ax > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    if (ay > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                                }
                                send(code)
                                ax = 0f
                                ay = 0f
                            }
                            change.consume()
                        }
                    }
                    if (!moved) send(KeyEvent.KEYCODE_DPAD_CENTER)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Swipe to move · tap to select", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp)
    }
}

/**
 * Permanent Fix for Keypad Text Transmission:
 * Alphanumeric characters are packaged and sent as absolute text strings / native IME inputs
 * rather than discrete positional navigation keys.
 */
@Composable
private fun Keypad(
    onCharPress: (String) -> Unit,
    onKeyPress: (Int) -> Unit,
) {
    val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (index == 2) 14.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { ch ->
                    Cap(ch.toString(), Modifier.weight(1f)) {
                        // Deliver absolute character string directly
                        onCharPress(ch.toString())
                    }
                }
                if (index == 3) {
                    Cap("⌫", Modifier.weight(1.6f), repeat = true) {
                        onKeyPress(KeyEvent.KEYCODE_DEL)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cap("←", Modifier.weight(1f)) { onKeyPress(KeyEvent.KEYCODE_DPAD_LEFT) }
            Cap("↑", Modifier.weight(1f)) { onKeyPress(KeyEvent.KEYCODE_DPAD_UP) }
            Cap("↓", Modifier.weight(1f)) { onKeyPress(KeyEvent.KEYCODE_DPAD_DOWN) }
            Cap("→", Modifier.weight(1f)) { onKeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Cap("space", Modifier.weight(2.2f)) { onCharPress(" ") }
            Cap("Go", Modifier.weight(1.3f)) { onKeyPress(KeyEvent.KEYCODE_ENTER) }
        }
    }
}

@Composable
private fun Cap(label: String, modifier: Modifier, repeat: Boolean = false, onTap: () -> Unit) {
    KeyButton(modifier.height(46.dp), RoundedCornerShape(12.dp), repeat = repeat, onPressed = onTap) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillControl(
    label: String, upIcon: ImageVector, downIcon: ImageVector,
    upCode: Int, downCode: Int, press: (Int) -> Unit,
) {
    Column(
        Modifier.width(84.dp).height(196.dp).glass(RoundedCornerShape(42.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().holdKey(repeat = true, onPressed = { press(upCode) }),
            contentAlignment = Alignment.Center,
        ) { Glyph(upIcon, 28.dp) }
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.weight(1f).fillMaxWidth().holdKey(repeat = true, onPressed = { press(downCode) }),
            contentAlignment = Alignment.Center,
        ) { Glyph(downIcon, 28.dp) }
    }
}

@Composable
private fun AppTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    KeyButton(Modifier.size(76.dp), RoundedCornerShape(24.dp), onPressed = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(icon, 24.dp)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun AppPicker(onDismiss: () -> Unit, onLaunch: (String) -> Unit, onTvList: () -> Unit) {
    val panel = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(18.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(panel)
                .background(Color(0xFF1A1C23))
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Open an app", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            APPS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { app ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(chip)
                                .background(Color(0xFF2A2D37))
                                .clickable { onLaunch(app.target) },
                            contentAlignment = Alignment.Center,
                        ) { Text(app.label, color = Color.White, fontSize = 14.sp) }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(chip)
                    .background(Color(0xFF2A2D37))
                    .clickable(onClick = onTvList),
                contentAlignment = Alignment.Center,
            ) { Text("Show the TV's own app list", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, size: Dp, tint: Color = Color.White.copy(alpha = 0.92f)) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun KeyButton(
    modifier: Modifier,
    shape: Shape,
    repeat: Boolean = false,
    onPressed: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    Box(
        modifier
            .scale(scale)
            .glass(shape, strong = pressed)
            .holdKey(repeat, onPressed) { pressed = it },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Fires on touch-down with a clean haptic tick; if [repeat], keeps firing while held. */
@Composable
private fun Modifier.holdKey(
    repeat: Boolean,
    onPressed: () -> Unit,
    onDown: (Boolean) -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(onPressed)
    val downCallback by rememberUpdatedState(onDown)
    return pointerInput(repeat) {
        detectTapGestures(onPress = {
            downCallback(true)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            current()
            val job = if (repeat) scope.launch {
                delay(450)
                while (true) { current(); delay(110) }
            } else null
            tryAwaitRelease()
            job?.cancel()
            downCallback(false)
        })
    }
}
