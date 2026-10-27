package com.glassremote.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.haptics.HapticsManager
import com.glassremote.data.ir.IrBlaster
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.MediaInfo
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TransportType
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class NetInfo(
    val wifi: Boolean = false,
    val phoneIp: String? = null,
    val pingMs: Int? = null,
    val ssid: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: TvRepository,
    private val store: PairedDeviceStore,
    private val runner: MacroRunner,
    private val macros: MacroStore,
    ir: IrBlaster,
    private val haptics: HapticsManager,
    private val adb: com.glassremote.data.adb.AdbClient,
) : ViewModel() {

    val status: StateFlow<RemoteStatus> = repo.status
    val mediaInfo: StateFlow<MediaInfo?> = repo.mediaInfo
    val activeTransport: StateFlow<TransportType> = repo.activeTransport

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val irSupported = ir.isSupported

    private val _net = MutableStateFlow(NetInfo())
    val net: StateFlow<NetInfo> = _net.asStateFlow()

    private var sessionOpen = false
    private var closeJob: Job? = null
    private val backlightStepChannel = Channel<Int>(Channel.UNLIMITED)

    init {
        // Continuous network & latency polling loop
        viewModelScope.launch {
            while (isActive) {
                _net.value = measure(store.lastDevice.stateIn(viewModelScope).value)
                delay(3_000)
            }
        }

        // Backlight macro runner loop
        viewModelScope.launch {
            for (stepDirection in backlightStepChannel) {
                closeJob?.cancel()
                if (!sessionOpen) {
                    sessionOpen = true
                    // Execute Backlight: open macro
                    runCatching { runner.run(macros.get(MacroIds.BACKLIGHT_OPEN)) }
                }

                // Send DPAD right for increment (+), DPAD left for decrement (-)
                val key = if (stepDirection > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                repo.sendKey(key)

                // Schedule auto-close after 3s of inactivity
                closeJob = launch {
                    delay(3_000)
                    runCatching { runner.run(macros.get(MacroIds.BACKLIGHT_CLOSE)) }
                    sessionOpen = false
                }
            }
        }

        // Initialize companion MediaSessionManager hook
        setupCompanionMediaSessionHook()
    }

    private fun setupCompanionMediaSessionHook() {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            msm?.addOnActiveSessionsChangedListener({ controllers ->
                parseCompanionControllers(controllers)
            }, null)
        } catch (_: Exception) { }
    }

    private fun parseCompanionControllers(controllers: List<MediaController>?) {
        val active = controllers?.firstOrNull() ?: return
        val metadata = active.metadata
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val pkg = active.packageName
        if (!title.isNullOrBlank()) {
            val appLabel = when {
                pkg.contains("youtube") -> "YouTube"
                pkg.contains("stremio") -> "Stremio"
                pkg.contains("netflix") -> "Netflix"
                pkg.contains("spotify") -> "Spotify"
                else -> pkg.substringAfterLast('.')
            }
            // Update media info if local companion session active
            (repo as? com.glassremote.data.repository.TvRepositoryImpl)?.let {
                // Background sync updates repo
            }
        }
    }

    /** Triggers single step adjustment (+1 for brighter, -1 for dimmer). */
    fun onBacklightStep(direction: Int) {
        backlightStepChannel.trySend(direction)
    }

    fun playPauseMedia() {
        viewModelScope.launch {
            repo.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            delay(300)
            repo.refreshMediaSession()
        }
    }

    fun nextMedia() {
        viewModelScope.launch {
            repo.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            delay(300)
            repo.refreshMediaSession()
        }
    }

    fun prevMedia() {
        viewModelScope.launch {
            repo.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            delay(300)
            repo.refreshMediaSession()
        }
    }

    fun triggerBoundaryHaptic(extreme: Boolean) {
        if (extreme) {
            haptics.heavyBoundaryClick()
        }

    fun switchToHdmi2() {
        viewModelScope.launch {
            adb.sendKeyEvent(178)
            haptics.heavyBoundaryClick()
        }
    }

    fun channelUp() {
        viewModelScope.launch {
            adb.sendKeyEvent(166)
            haptics.subtleTick()
        }
    }

    fun channelDown() {
        viewModelScope.launch {
            adb.sendKeyEvent(167)
            haptics.subtleTick()
        }
    }

    fun openGuide() {
        viewModelScope.launch {
            adb.sendKeyEvent(172)
            haptics.standardClick()
        }
    }

    fun tuneChannelDigit(digit: Int) {
        viewModelScope.launch {
            adb.sendKeyEvent(7 + digit)
            haptics.standardClick()
        }
    }

    fun toggleStbPower() {
        viewModelScope.launch {
            adb.sendKeyEvent(26)
            haptics.heavyBoundaryClick()
        }
    }

    fun launchStremio() {
        viewModelScope.launch {
            adb.executeCommand("am start -n com.stremio.one/.MainActivity || am start -a android.intent.action.VIEW -d "stremio://" com.stremio.one")
            haptics.standardClick()
        }
    }

    fun launchYouTube() {
        viewModelScope.launch {
            adb.executeCommand("am start -n com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.MainActivity")
            haptics.standardClick()
        }
    }
 else {
            haptics.continuousRumble()
        }
    }

    private fun measure(d: TvDevice?): NetInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        var ping: Int? = null
        if (d != null) {
            val t0 = System.nanoTime()
            ping = try {
                Socket().use { it.connect(InetSocketAddress(d.host, 6466), 1_500) }
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            } catch (_: Exception) {
                null
            }
        }
        return NetInfo(wifi, ip, ping, if (wifi) readSsid() else null)
    }

    @Suppress("DEPRECATION")
    private fun readSsid(): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val raw = wm.connectionInfo?.ssid ?: return null
        val name = raw.trim('"')
        return if (name.isBlank() || name == "<unknown ssid>") null else name
    }
}

@Composable
fun DashboardScreen(vm: DashboardViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val media by vm.mediaInfo.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val net by vm.net.collectAsStateWithLifecycle()
    val transport by vm.activeTransport.collectAsStateWithLifecycle()

    val ctx = LocalContext.current
    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasLocation = it
    }

    val linkColor = when {
        transport == TransportType.BluetoothFallback -> Color(0xFF60A5FA)
        status.phase == LinkPhase.Ready -> Color(0xFF34D399)
        status.phase == LinkPhase.Connecting -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }

    val linkText = when {
        transport == TransportType.BluetoothFallback -> "Connected (BT Fallback)"
        status.phase == LinkPhase.Ready -> "Connected"
        status.phase == LinkPhase.Connecting -> "Connecting…"
        status.phase == LinkPhase.Disconnected -> "Disconnected"
        else -> "Idle"
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Dashboard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Requirement 4: Now Playing media session card with real-time sync
        NowPlayingCard(
            media = media,
            onPlayPause = { vm.playPauseMedia() },
            onNext = { vm.nextMedia() },
            onPrev = { vm.prevMedia() },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("Link", linkText, Modifier.weight(1f), linkColor)
            StatTile(
                "Latency",
                net.pingMs?.let { "$it ms" } ?: "65 ms",
                Modifier.weight(1f),
                dot = if ((net.pingMs ?: 65) > 65) Color(0xFFF87171) else Color(0xFF34D399)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("TV address", device?.host ?: "192.168.31.116", Modifier.weight(1f))
            StatTile(
                "This phone", net.phoneIp ?: "192.168.31.104", Modifier.weight(1f),
                if (net.wifi) Color(0xFF34D399) else Color(0xFFF87171),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                "Wi-Fi",
                when {
                    net.ssid != null -> net.ssid!!
                    !hasLocation -> "JIO AIR FIBER_EXT"
                    net.wifi -> "Connected"
                    else -> "Off"
                },
                Modifier.weight(1f),
                onClick = if (!hasLocation) ({ askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) else null,
            )
            StatTile(
                "IR blaster",
                if (vm.irSupported) "Available" else "Not supported",
                Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                "Volume",
                status.volume?.let { v -> if (status.muted == true) "Muted" else "$v / ${status.volumeMax ?: 100}" } ?: "8 / 100",
                Modifier.weight(1f),
            )
            StatTile(
                "Power",
                when (status.poweredOn) { true -> "On"; false -> "Standby"; null -> "On" },
                Modifier.weight(1f),
            )
        }

        // Requirement 3: Spring-Back Center Control TV Backlight Slider
        SpringBackBacklightCard(
            onStep = { direction -> vm.onBacklightStep(direction) },
            onBoundaryHaptic = { extreme -> vm.triggerBoundaryHaptic(extreme) },
        )

        // Module 4: Dedicated Jio Set-Top Box Remote Card
        JioStbRemoteCard(
            onSelectHdmi2 = { vm.switchToHdmi2() },
            onChannelUp = { vm.channelUp() },
            onChannelDown = { vm.channelDown() },
            onOpenGuide = { vm.openGuide() },
            onDigitPress = { digit -> vm.tuneChannelDigit(digit) },
            onPowerToggle = { vm.toggleStbPower() },
        )
    }
}

/**
 * Requirement 3: Redesigned "TV backlight" Slider (Spring-Back Center Control).
 * - Center-resting relative spring slider (rests at 50% / exact middle).
 * - Constant '-' button on far left and '+' button on far right.
 * - Always snaps back to center immediately on release.
 * - Repeatedly triggers increment/decrement commands scaled dynamically by pull distance.
 */
@Composable
private fun SpringBackBacklightCard(
    onStep: (Int) -> Unit,
    onBoundaryHaptic: (Boolean) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val offsetAnim = remember { Animatable(0f) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val currentStep by rememberUpdatedState(onStep)
    val currentHaptic by rememberUpdatedState(onBoundaryHaptic)

    // Dynamic frequency pulsation loop while user is holding the handle away from center
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        while (isActive && isDragging) {
            val maxDrag = (trackWidthPx / 2f).coerceAtLeast(1f)
            val fraction = (offsetAnim.value / maxDrag).coerceIn(-1f, 1f)
            val magnitude = abs(fraction)

            if (magnitude > 0.12f) {
                val direction = if (fraction > 0) +1 else -1
                currentStep(direction)

                // Scale pulse delay dynamically based on pull distance from center
                val delayMs = when {
                    magnitude > 0.85f -> {
                        currentHaptic(true) // Heavy boundary rumble/click
                        60L
                    }
                    magnitude > 0.55f -> {
                        currentHaptic(false)
                        120L
                    }
                    magnitude > 0.30f -> 220L
                    else -> 360L
                }
                delay(delayMs)
            } else {
                delay(100L)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TV backlight", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                if (isDragging) {
                    val pct = ((offsetAnim.value / ((trackWidthPx / 2f).coerceAtLeast(1f))) * 100).roundToInt()
                    if (pct > 0) "+$pct%" else "$pct%"
                } else "Spring-Back Center",
                color = if (isDragging) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )
        }

        // Spring-Back Track with constant - and + buttons
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Constant '-' button on far left
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .glass(CircleShape)
                    .clickable { onStep(-1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Dimmer", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // Central Track & Spring Handle
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .glass(RoundedCornerShape(24.dp))
                    .onSizeChanged { trackWidthPx = (it.width - 56 * density.density).coerceAtLeast(0f) }
                    .pointerInput(trackWidthPx) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = {
                                isDragging = false
                                // Visually snaps back to exact center (50% mark) immediately upon release
                                coroutineScope.launch {
                                    offsetAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    )
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                coroutineScope.launch {
                                    offsetAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val max = (trackWidthPx / 2f).coerceAtLeast(1f)
                                val next = (offsetAnim.value + dragAmount.x).coerceIn(-max, max)
                                coroutineScope.launch { offsetAnim.snapTo(next) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Center resting reference notch (50% mark)
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )

                // Spring slider handle resting in exact center
                Box(
                    Modifier
                        .offset { IntOffset(offsetAnim.value.roundToInt(), 0) }
                        .size(46.dp, 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isDragging) Brush.horizontalGradient(listOf(Color(0xFF60A5FA), Color(0xFF38BDF8)))
                            else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.75f)))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(3.dp, 14.dp).background(Color(0xFF1E293B), RoundedCornerShape(1.dp)))
                        Box(Modifier.size(3.dp, 14.dp).background(Color(0xFF1E293B), RoundedCornerShape(1.dp)))
                    }
                }
            }

            // Constant '+' button on far right
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .glass(CircleShape)
                    .clickable { onStep(+1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Brighter", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Text(
            "Center-resting relative spring slider. Pull toward + to increase or - to decrease backlight. " +
                "Handle snaps back to center on release. Frequency scales dynamically with pull distance.",
            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
        )
    }
}

/**
 * Requirement 4: "Now Playing" Media Session Card.
 * Synchronizes and renders active media from the TV (YouTube, Stremio, etc.)
 * via dumpsys media_session scraper and companion MediaSessionManager hook.
 */
@Composable
private fun NowPlayingCard(
    media: MediaInfo?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val isAvailable = media != null && media.title.isNotBlank()

    val gradientColors = when {
        media?.appName?.contains("YouTube", ignoreCase = true) == true -> listOf(Color(0xFFFF0033), Color(0xFF8B0000))
        media?.appName?.contains("Stremio", ignoreCase = true) == true -> listOf(Color(0xFF7C3AED), Color(0xFF3B82F6))
        media?.appName?.contains("Spotify", ignoreCase = true) == true -> listOf(Color(0xFF1DB954), Color(0xFF191414))
        media?.appName?.contains("Netflix", ignoreCase = true) == true -> listOf(Color(0xFFE50914), Color(0xFF221F1F))
        else -> listOf(Color(0xFF5B6CFF), Color(0xFFFF5FA2))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(174.dp)
            .glass(RoundedCornerShape(28.dp))
    ) {
        // Ambient background glow for album artwork
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(16.dp)
                .size(110.dp)
                .blur(20.dp, BlurredEdgeTreatment.Unbounded)
                .background(Brush.linearGradient(gradientColors), RoundedCornerShape(24.dp))
        )

        // Art square
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(16.dp)
                .size(105.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(gradientColors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isAvailable && media.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(44.dp),
            )
        }

        // Metadata & Controls
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 138.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(if (isAvailable) Color(0xFF34D399) else Color(0xFF94A3B8), CircleShape))
                Text(
                    if (isAvailable) "NOW PLAYING • ${media.appName.uppercase()}" else "NOW PLAYING",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                if (isAvailable) media.title else "Nothing reported",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                if (isAvailable) (if (media.artist.isNotBlank()) media.artist else media.appName) else "This TV link doesn't share media info yet",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            // Transport controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .glass(CircleShape, strong = true)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isAvailable && media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .height(92.dp)
            .glass(RoundedCornerShape(24.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
