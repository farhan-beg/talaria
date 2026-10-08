package dev.hark.hermes.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hark.hermes.R
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun ConnectScreen() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = app.store.auth.value
    var url by remember { mutableStateOf(saved.baseUrl) }
    var name by remember { mutableStateOf(saved.name) }
    val others by app.store.servers.collectAsState()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var pwProviders by remember { mutableStateOf<List<String>>(emptyList()) }
    var user by remember { mutableStateOf(saved.userId) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    fun probe() = scope.launch {
        busy = true; err = null; status = null
        try {
            val u = app.api.normalize(url); url = u
            val st = app.api.probe(u)
            pwProviders = if (st.b("auth_required")) app.auth.passwordProviders(u) else emptyList()
            status = st
        } catch (e: Exception) { err = errText(e) }
        busy = false
    }

    fun signIn() = app.scope.launch {
        busy = true; err = null
        try {
            val st = status!!
            if (!st.b("auth_required")) {
                app.store.update { AuthState(baseUrl = url, authRequired = false) }
            } else {
                app.auth.signIn(url, pwProviders.firstOrNull() ?: "basic", user.trim(), pass)
            }
            if (name.isNotBlank()) app.store.update { it.copy(name = name.trim()) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { err = errText(e) }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(p.heroA, p.heroB))), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_fg), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(84.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("TALARIA  ·  FOR HERMES AGENT", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp), color = p.muted)
        Spacer(Modifier.height(10.dp))
        Text("Your agent,\nin your pocket.", style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(10.dp))
        Text("Connect to the Hermes dashboard running on your server. You'll sign in right here with your dashboard username and password.",
            style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(28.dp))
        val saved2 = others.filter { it.auth.isSignedIn }
        if (saved2.isNotEmpty()) {
            Text("Your servers", style = MaterialTheme.typography.labelLarge, color = p.muted)
            Spacer(Modifier.height(8.dp))
            saved2.forEach { sv ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(p.card).then(Modifier.clickable { switchServer(sv.id) }).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = p.accent); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sv.auth.label, color = p.ink, style = MaterialTheme.typography.titleSmall)
                        Text(sv.auth.host, color = p.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, tint = p.faint)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Or add another", style = MaterialTheme.typography.labelLarge, color = p.muted)
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            name, { name = it }, label = { Text("Name (optional)") }, singleLine = true,
            placeholder = { Text("Home lab, VPS, Work…", color = p.faint) },
            leadingIcon = { Icon(Icons.Outlined.Label, null, tint = p.muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line, focusedBorderColor = p.ink.copy(alpha = 0.5f), focusedLabelColor = p.ink, cursorColor = p.ink),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            url, { url = it; status = null }, label = { Text("Dashboard URL") }, singleLine = true,
            placeholder = { Text("https://hermes.example.com", color = p.faint) },
            leadingIcon = { Icon(Icons.Outlined.Dns, null, tint = p.muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { probe() }),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line, focusedBorderColor = p.ink.copy(alpha = 0.5f), focusedLabelColor = p.ink, cursorColor = p.ink),
        )
        Spacer(Modifier.height(14.dp))

        val st = status
        if (st == null) {
            Button(
                onClick = { probe() }, enabled = url.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accentInk) else Text("Continue")
            }
        } else {
            HCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(p.good); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Hermes ${st.s("version")}", style = MaterialTheme.typography.titleMedium, color = p.ink)
                        Text(if (st.b("gateway_running")) "Gateway running" else "Gateway stopped", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    val providers = st.a("auth_providers").strs()
                    if (providers.isNotEmpty()) Pill(providers.joinToString(" · "))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!st.b("auth_required")) {
                PrimaryAction("Open dashboard", Icons.Outlined.ArrowForward, busy) { signIn() }
                Text("This dashboard has no sign-in gate (loopback bind).", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            } else if (pwProviders.isNotEmpty()) {
                val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line, focusedBorderColor = p.ink.copy(alpha = 0.5f), focusedLabelColor = p.ink, cursorColor = p.ink)
                OutlinedTextField(
                    user, { user = it }, label = { Text("Username") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Person, null, tint = p.muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = p.muted) },
                    trailingIcon = { IconButton({ showPass = !showPass }) { Icon(if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = p.muted) } },
                    visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (user.isNotBlank() && pass.isNotEmpty()) signIn() }),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryAction("Sign in", Icons.Outlined.Login, busy, enabled = user.isNotBlank() && pass.isNotEmpty()) { signIn() }
            } else {
                HCard {
                    Text("Turn on password sign-in", style = MaterialTheme.typography.titleMedium, color = p.ink)
                    Spacer(Modifier.height(6.dp))
                    Text("This app signs in without a browser, using the dashboard's username & password login. Add this to ~/.hermes/config.yaml on your server, restart the dashboard, then tap Check again.",
                        style = MaterialTheme.typography.bodySmall, color = p.muted)
                    Spacer(Modifier.height(10.dp))
                    Text("dashboard:\n  basic_auth:\n    username: farhan\n    password: choose-a-strong-one\n    secret: any-long-random-string",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = p.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(12.dp))
                }
                Spacer(Modifier.height(14.dp))
                PrimaryAction("Check again", Icons.Outlined.Refresh, busy) { probe() }
            }
        }

        if (url.isNotBlank() && dev.hark.hermes.data.isInsecureUrl(app.api.normalize(url))) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bad.copy(alpha = 0.1f)).padding(12.dp)) {
                Icon(Icons.Outlined.LockOpen, null, tint = p.bad); Spacer(Modifier.width(8.dp))
                Text("This is plain http to a public address, so your password and chats travel unencrypted. Use https, or a LAN or Tailscale address.", color = p.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        err?.let {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bad.copy(alpha = 0.1f)).padding(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = p.bad); Spacer(Modifier.width(8.dp))
                Text(it, color = p.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(30.dp))
        Text("Tip: start it with  hermes dashboard --host 0.0.0.0 --port 9119",
            style = MaterialTheme.typography.bodySmall, color = p.faint)
    }
}

@Composable
private fun PrimaryAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(
        onClick = onClick, enabled = !busy && enabled, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accentInk)
        else { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(text) }
    }
}


/** Moves the whole app to another saved server: drops the live chat, swaps tokens, reconnects. */
fun switchServer(id: String) {
    // the server you leave keeps its own connection (and any chat running there); this one comes up beside it
    app.store.switchTo(id)
    if (app.store.auth.value.isSignedIn) app.gatewayFor(id).connect()
}
