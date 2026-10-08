package dev.hark.hermes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import dev.hark.hermes.app
import dev.hark.hermes.ui.screens.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val theme by app.store.theme.collectAsStateWithLifecycle()
            val preset by app.store.palette.collectAsStateWithLifecycle()
            val glass by app.store.glass.collectAsStateWithLifecycle()
            val motion by app.store.motion.collectAsStateWithLifecycle()
            val ambient by app.store.ambient.collectAsStateWithLifecycle()
            HermesTheme(theme, preset, Look(glass, motion, ambient)) { Root() }
        }
        openFrom(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A notification from a chat (possibly on another server): bring that server and that chat up. */
    private fun openFrom(i: android.content.Intent?) {
        val server = i?.getStringExtra(dev.hark.hermes.TurnService.EXTRA_SERVER).orEmpty()
        val chat = i?.getStringExtra(dev.hark.hermes.TurnService.EXTRA_CHAT).orEmpty()
        if (server.isBlank() && chat.isBlank()) return
        i?.removeExtra(dev.hark.hermes.TurnService.EXTRA_SERVER); i?.removeExtra(dev.hark.hermes.TurnService.EXTRA_CHAT)
        if (server.isNotBlank() && server != app.store.activeId.value && app.store.servers.value.any { it.id == server }) switchServer(server)
        val g = app.gatewayFor(server.ifBlank { app.store.activeId.value })
        if (chat.isNotBlank() && chat != g.storedSid) ChatNav.pendingResume.value = chat to null
        NotifNav.openChat.value = true
    }

    override fun onResume() {
        super.onResume()
        app.foreground = true
        app.gateway.wake()
        androidx.core.app.NotificationManagerCompat.from(this).cancel(dev.hark.hermes.TurnService.DONE_ID)
    }

    override fun onPause() {
        app.foreground = false
        super.onPause()
    }
}

@Composable
fun Root() {
    val auth by app.store.auth.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(p.bgTop, p.bg, p.bgBottom)))) {
        AmbientGlow()
        if (!auth.isSignedIn) ConnectScreen() else Shell()
        if (auth.isSignedIn) FileViewerHost()
        SnackbarHost(Toaster.host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 90.dp)) {
            Snackbar(it, shape = RoundedCornerShape(20.dp), containerColor = p.ink, contentColor = p.accentInk)
        }
    }
}

/** Two soft light pools that drift slowly behind everything, like light through liquid. */
@Composable
private fun AmbientGlow() {
    val p = LocalPalette.current
    val look = LocalLook.current
    val t = if (look.ambient && look.motion) {
        val inf = rememberInfiniteTransition(label = "amb")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t").value
    } else 0.5f
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val c1 = androidx.compose.ui.geometry.Offset(w * (0.85f - 0.25f * t), h * (0.92f - 0.08f * t))
        val c2 = androidx.compose.ui.geometry.Offset(w * (0.05f + 0.2f * t), h * (0.18f + 0.12f * t))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(p.glow, androidx.compose.ui.graphics.Color.Transparent), c1, w * 1.05f), w * 1.05f, c1)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(p.glow.copy(alpha = p.glow.alpha * 0.55f), androidx.compose.ui.graphics.Color.Transparent), c2, w * 0.7f), w * 0.7f, c2)
    }
}

/** The five swipeable tabs, in dock order. */
private data class TabDef(val route: String, val label: String, val icon: ImageVector)
private val dock = listOf(
    TabDef("home", "Home", Icons.Outlined.SpaceDashboard),
    TabDef("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    TabDef("cron", "Tasks", Icons.Outlined.Schedule),
    TabDef("sessions", "History", Icons.Outlined.History),
    TabDef("more", "More", Icons.Outlined.Apps),
)
val tabRoutes = dock.map { it.route }

/** Set when a notification asks for the chat tab. */
object NotifNav { val openChat = kotlinx.coroutines.flow.MutableStateFlow(false) }

/** Lets non-composable navigation (nav.go) switch the pager. */
object TabBus { var select: ((String) -> Unit)? = null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shell() {
    val nav = rememberNavController()
    val p = LocalPalette.current
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.api.expired.collect { expired = true } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "tabs"
    val onTab = route == "tabs"

    val pager = androidx.compose.foundation.pager.rememberPagerState { dock.size }
    val tabSpring = if (look.motion) spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow) else tween(160)
    val select: (String) -> Unit = { r ->
        val i = tabRoutes.indexOf(r)
        if (i >= 0) scope.launch { pager.animateScrollToPage(i, animationSpec = tabSpring) }
    }
    DisposableEffect(Unit) { TabBus.select = select; onDispose { TabBus.select = null } }
    LaunchedEffect(Unit) { NotifNav.openChat.collect { if (it) { NotifNav.openChat.value = false; nav.popBackStack("tabs", false); select("chat") } } }
    androidx.activity.compose.BackHandler(enabled = onTab && pager.currentPage != 0) { select("home") }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "tabs",
            enterTransition = { fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f) },
            exitTransition = { fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.02f) },
            popEnterTransition = { fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 1.03f) },
            popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.96f) },
            modifier = Modifier.fillMaxSize()) {
            composable("tabs") {
                CompositionLocalProvider(LocalTopInset provides 76.dp) {
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                        key = { dock[it].route },
                    ) { page ->
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            // a soft depth cue while swiping: the leaving page eases back and dims a touch
                            val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).let { kotlin.math.abs(it) }.coerceIn(0f, 1f)
                            if (look.motion) { val sc = 1f - 0.05f * off; scaleX = sc; scaleY = sc; alpha = 1f - 0.35f * off }
                        }) {
                            when (dock[page].route) {
                                "home" -> HomeScreen(nav)
                                "chat" -> { val ag by app.activeGateway.collectAsStateWithLifecycle(); key(ag) { ChatScreen(nav) } }
                                "cron" -> CronScreen(nav)
                                "sessions" -> SessionsScreen(nav)
                                else -> MoreScreen(nav)
                            }
                        }
                    }
                }
            }
            composable("session/{id}") { SessionDetailScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("analytics") { AnalyticsScreen(nav) }
            composable("skills") { SkillsScreen(nav) }
            composable("models") { ModelsScreen(nav) }
            composable("config") { ConfigScreen(nav) }
            composable("keys") { KeysScreen(nav) }
            composable("voice") { VoiceSettingsScreen(nav) }
            composable("mcp") { McpScreen(nav) }
            composable("channels") { ChannelsScreen(nav) }
            composable("pairing") { PairingScreen(nav) }
            composable("webhooks") { WebhooksScreen(nav) }
            composable("logs") { LogsScreen(nav) }
            composable("profiles") { ProfilesScreen(nav) }
            composable("system") { SystemScreen(nav) }
            composable("memory") { MemoryScreen(nav) }
            composable("files") { FilesScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
        }
        AnimatedVisibility(onTab, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            // continuous position: 1.4 means 40% of the way from Chat to Tasks
            TopNav({ pager.currentPage + pager.currentPageOffsetFraction }) { r -> select(r) }
        }
    }

    if (expired) {
        AlertDialog(
            onDismissRequest = {}, containerColor = p.sheet, shape = RoundedCornerShape(26.dp),
            title = { Text("Session expired") },
            text = { Text("Your dashboard sign-in ran out. Sign in again to keep going.\n\n" + (app.api.lastAuthError ?: ""), color = p.muted) },
            confirmButton = {
                TextButton({
                    expired = false; app.dropGateway(app.store.activeId.value); app.store.signOut()
                }) { Text("Sign in again", color = p.accent) }
            },
            dismissButton = { TextButton({ expired = false; app.dropGateway(app.store.activeId.value); app.store.signOut() }) { Text("Sign out", color = p.muted) } },
        )
    }
}

/**
 * The dock tracks the pager's live position, so the gold capsule and its label
 * morph between tabs frame by frame as you drag, not after the swipe settles.
 */
@Composable
private fun TopNav(pos: () -> Float, go: (String) -> Unit) {
    val p = LocalPalette.current
    val glass = if (p.dark) androidx.compose.ui.graphics.Color(0xF21A1430) else androidx.compose.ui.graphics.Color(0xF5FFFDF8)
    val shade = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f)
    val clear = androidx.compose.ui.graphics.Color.Transparent
    val position = pos()
    fun near(i: Int) = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(p.bgTop, p.bgTop.copy(alpha = 0.8f), clear)))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp).height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().shadow(18.dp, RoundedCornerShape(20.dp), ambientColor = shade, spotColor = shade)
                    .glass(RoundedCornerShape(20.dp), glass).padding(5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                dock.take(4).forEachIndexed { i, t ->
                    val s = near(i)
                    val fg = androidx.compose.ui.graphics.lerp(p.muted, p.accentInk, s)
                    Row(
                        Modifier.fillMaxHeight().press { go(t.route) }
                            .clip(RoundedCornerShape(15.dp)).background(p.accent.copy(alpha = p.accent.alpha * s))
                            .then(if (s > 0.5f) Modifier.sheen(RoundedCornerShape(15.dp)) else Modifier)
                            .padding(horizontal = androidx.compose.ui.unit.lerp(13.dp, 16.dp, s)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(22.dp))
                        if (s > 0.001f) RevealLabel(t.label, fg, s)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            val s = near(4)
            Box(
                Modifier.size(54.dp).graphicsLayer { val k = 1f + 0.06f * s; scaleX = k; scaleY = k }
                    .shadow(18.dp, RoundedCornerShape(20.dp), ambientColor = shade, spotColor = shade)
                    .press { go("more") }.glass(RoundedCornerShape(20.dp), androidx.compose.ui.graphics.lerp(glass, p.accent, s)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Apps, "More", tint = androidx.compose.ui.graphics.lerp(p.ink, p.accentInk, s), modifier = Modifier.size(24.dp)) }
        }
    }
}

/** A label whose width and opacity follow [fraction], so it unrolls with the swipe. */
@Composable
private fun RevealLabel(text: String, color: androidx.compose.ui.graphics.Color, fraction: Float) {
    Box(Modifier.clipToBounds().layout { m, c ->
        val gap = 8.dp.roundToPx()
        val pl = m.measure(c.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val w = ((pl.width + gap) * fraction).toInt()
        layout(w, pl.height) { pl.placeRelativeWithLayer(gap, 0) { alpha = fraction } }
    }) {
        Text(text, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
    }
}

fun NavHostController.go(route: String) {
    if (route in tabRoutes) {
        if (currentDestination?.route != "tabs") popBackStack("tabs", inclusive = false)
        TabBus.select?.invoke(route)
    } else navigate(route) { launchSingleTop = true }
}
