package com.frosst.gestordomestico

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.frosst.gestordomestico.link.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** LAN test screen. Leaving it stops the listener; no background server is claimed. */
class LinkActivity : ComponentActivity() {
    private lateinit var model: LinkSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[LinkSession::class.java]
        val mode = ThemeMode.fromValue(getSharedPreferences("gestor_domestico_settings", MODE_PRIVATE)
            .getString("theme_mode", null))
        setContent {
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LinkScreen(model) { finish() }
            }
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.pause()
        super.onStop()
    }
}

data class LinkUiState(
    val busy: Boolean = false,
    val addresses: List<String> = emptyList(),
    val deviceId: String = "",
    val endpoint: LinkEndpoint? = null,
    val invitation: String = "",
    val disk: LinkDiskState = LinkDiskState(),
    val message: String = "Todavia no hay un enlace comprobado."
)

class LinkSession(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(LinkUiState())
        private set
    private val store = AndroidLinkStore(application)
    private val lock = Any()
    private var generation = 0L
    private var server: LanLinkServer? = null
    private var gate: PairingGate? = null
    private var call: LinkCall? = null
    private var job: Job? = null
    init { refresh() }

    private suspend fun identity(): String = (getApplication<Application>() as ShoppingApplication)
        .database.syncJournalDao().deviceId()

    private fun localAddresses(): List<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        return interfaces.filter { it.isUp && !it.isLoopback }.flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().mapNotNull { address ->
                try { LanPolicy.address(address.hostAddress ?: "") } catch (_: IllegalArgumentException) { null }
            }.distinct().sorted()
    }

    private fun action(block: suspend (Long) -> String) {
        if (state.busy) return
        val current = synchronized(lock) { generation }
        state = state.copy(busy = true)
        job = viewModelScope.launch {
            try {
                val message = block(current)
                ensureActive()
                state = state.copy(disk = withContext(Dispatchers.IO) { store.state() }, message = message)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                // Do not surface exception strings that could contain network or credential details.
                val saved = try { withContext(Dispatchers.IO) { store.state() } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                state = state.copy(disk = saved ?: state.disk, message =
                    "No se completo la operacion. Revisa la Wi-Fi, invitacion y servidor. " +
                    "No se ha sincronizado ninguna compra. Si hay un intento guardado, usa Comprobar enlace.")
            } finally {
                if (synchronized(lock) { generation == current }) state = state.copy(busy = false)
            }
        }
    }

    fun refresh() = action {
        val snapshot = withContext(Dispatchers.IO) { Triple(localAddresses(), identity(), store.state()) }
        state = state.copy(addresses = snapshot.first, deviceId = snapshot.second, disk = snapshot.third)
        "Direcciones y vinculacion local cargadas."
    }

    fun start(host: String) = action { current ->
        val endpoint = withContext(Dispatchers.IO) {
            check(store.state().client == null) { "Ya existe una configuracion de cliente" }
            require(host in localAddresses()) { "La direccion no pertenece a este movil" }
            val identity = identity()
            val security = AndroidLinkTls.server()
            val nextGate = PairingGate(identity, store)
            val nextServer = LanLinkServer(security.first, nextGate, security.second, onPaired = {
                viewModelScope.launch {
                    if (synchronized(lock) { generation == current }) {
                        try {
                            val disk = withContext(Dispatchers.IO) { store.state() }
                            state = state.copy(disk = disk, invitation = "", message =
                                "Cliente autorizado. La sincronizacion de compras sigue pendiente.")
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { state = state.copy(message = "No se pudo leer el estado del enlace.") }
                    }
                }
            })
            val result = nextServer.start(host)
            synchronized(lock) {
                if (generation != current || server != null) {
                    nextServer.close(); throw CancellationException()
                }
                server = nextServer
                gate = nextGate
            }
            result
        }
        state = state.copy(endpoint = endpoint)
        "Servidor LAN iniciado. Se detiene al salir de esta pantalla o mandar la app al fondo."
    }

    fun invite() = action {
        val endpoint = state.endpoint ?: error("Servidor detenido")
        val activeGate = synchronized(lock) { gate } ?: error("Servidor detenido")
        val invitation = withContext(Dispatchers.IO) { activeGate.invite(endpoint) }
        state = state.copy(invitation = invitation.encode())
        "Invitacion valida durante 5 minutos para un solo cliente. Compartela solo con el otro movil."
    }

    private fun registerCall(current: Long): LinkCall = synchronized(lock) {
        if (generation != current) throw CancellationException()
        LinkCall().also { call = it }
    }

    fun join(text: String) = action { current ->
        val protocol = withContext(Dispatchers.IO) {
            val existing = store.state()
            check(existing.peer == null && existing.client == null)
            check(synchronized(lock) { server == null })
            val invitation = LinkInvitation.parse(text)
            val client = ClientLink(invitation.endpoint, identity(), LinkSecrets.newToken())
            require(client.deviceId != client.endpoint.serverId)
            val connection = registerCall(current)
            try {
                // Persist BEFORE sending. A lost reply can be recovered with an authenticated probe.
                store.saveClient(client)
                val negotiated = LanLinkClient.pair(invitation, client, connection)
                store.saveClient(client.copy(confirmed = true))
                negotiated
            } finally { connection.close() }
        }
        "Vinculacion confirmada mediante $protocol. Las compras aun no se sincronizan."
    }

    fun probe() = action { current ->
        val protocol = withContext(Dispatchers.IO) {
            val client = store.state().client ?: error("No hay cliente guardado")
            require(client.deviceId == identity())
            val connection = registerCall(current)
            try {
                val negotiated = LanLinkClient.probe(client, connection)
                store.saveClient(client.copy(confirmed = true))
                negotiated
            } finally { connection.close() }
        }
        "Enlace comprobado: $protocol, servidor y credencial verificados. Sin intercambio de compras todavia."
    }

    fun forget() {
        pause()
        action { withContext(Dispatchers.IO) { store.forget() }; "Vinculacion eliminada en este movil." }
    }

    fun pause() {
        synchronized(lock) {
            generation++
            server?.close(); server = null
            gate = null
            call?.close(); call = null
        }
        job?.cancel(); job = null
        state = state.copy(busy = false, endpoint = null, invitation = "", message =
            "Conexion detenida. Las credenciales guardadas se conservan; no hay servidor en segundo plano.")
    }
    override fun onCleared() { pause(); super.onCleared() }
}

@Composable
private fun LinkScreen(model: LinkSession, onBack: () -> Unit) {
    val state = model.state
    var address by rememberSaveable { mutableStateOf("") }
    // Invitation secrets stay in memory, not in saved-instance-state or application backups.
    var invitation by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(state.addresses) { if (address !in state.addresses) address = state.addresses.firstOrNull().orEmpty() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Volver a compras") }
            Text("Dispositivos · prueba LAN", style = MaterialTheme.typography.headlineSmall)
            Text("Esta fase vincula y comprueba el canal cifrado. Las listas TODAVIA NO se sincronizan.",
                color = MaterialTheme.colorScheme.primary)
            Text("Ambos moviles en la misma Wi-Fi y con esta pantalla abierta. No hay servidor permanente.")
            if (state.deviceId.isNotEmpty()) Text("Este dispositivo: ${state.deviceId.takeLast(8)}")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            if (state.disk.client == null) {
                Text("Movil servidor", style = MaterialTheme.typography.titleMedium)
                if (state.endpoint == null) {
                    state.addresses.forEach { ip ->
                        Row {
                            RadioButton(selected = address == ip, enabled = !state.busy, onClick = { address = ip })
                            TextButton(enabled = !state.busy, onClick = { address = ip }) { Text(ip) }
                        }
                    }
                    if (state.addresses.isEmpty()) Text("No se ha encontrado una IPv4 privada. Conecta la Wi-Fi y actualiza.")
                    TextButton(enabled = !state.busy, onClick = model::refresh) { Text("Actualizar direcciones") }
                    Button(enabled = !state.busy && address.isNotEmpty(), onClick = { model.start(address) }) {
                        Text("Arrancar servidor LAN")
                    }
                } else {
                    Text("Escuchando en ${state.endpoint.host}:${state.endpoint.port}")
                    OutlinedButton(onClick = model::pause) { Text("Detener servidor") }
                    if (state.disk.peer == null) Button(enabled = !state.busy, onClick = model::invite) { Text("Crear invitacion") }
                }
                if (state.invitation.isNotEmpty()) {
                    Text("SECRETO: no publicar ni enviar a terceros. Selecciona y copia al otro movil.", color = MaterialTheme.colorScheme.error)
                    SelectionContainer { Text(state.invitation, style = MaterialTheme.typography.bodySmall) }
                }
                state.disk.peer?.let { Text("Cliente autorizado: ${it.deviceId.takeLast(8)}") }
            }
            if (state.endpoint == null && state.disk.peer == null) {
                Text("Movil cliente", style = MaterialTheme.typography.titleMedium)
                val client = state.disk.client
                if (client == null) {
                    OutlinedTextField(value = invitation, onValueChange = { if (it.length <= 512) invitation = it },
                        enabled = !state.busy, label = { Text("Pegar invitacion del servidor") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Button(enabled = !state.busy && invitation.isNotBlank(), onClick = {
                        val value = invitation; invitation = ""; model.join(value)
                    }) { Text("Vincular este movil") }
                } else {
                    Text("Servidor guardado: ${client.endpoint.host}:${client.endpoint.port}")
                    Text(if (client.confirmed) "Vinculado; esto no significa que el servidor este conectado ahora."
                        else "Intento guardado, pendiente de confirmacion. Una respuesta pudo haberse perdido.")
                    Button(enabled = !state.busy, onClick = model::probe) { Text("Comprobar enlace") }
                }
            }
            if (state.disk.peer != null || state.disk.client != null) {
                OutlinedButton(enabled = !state.busy, onClick = { confirmForget = true }) {
                    Text(if (state.disk.peer != null) "Revocar cliente y detener" else "Olvidar servidor")
                }
            }
            Text("No se envia ni modifica ninguna compra en esta fase. No abras puertos del router.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false }, title = { Text("Eliminar vinculacion") },
        text = { Text(if (state.disk.peer != null) "El cliente perdera acceso a este servidor. Las compras no se borran."
            else "Solo se borra la credencial de este movil. Para revocar el acceso, hazlo tambien en el servidor. Las compras no se borran.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; model.forget() }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } }
    )
}
