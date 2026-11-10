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
import com.frosst.gestordomestico.exchange.*
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

/** Manual LAN exchange. Leaving this screen still stops the listener. */
class LinkActivity : ComponentActivity() {
    private lateinit var model: LinkSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[LinkSession::class.java]
        val mode = ThemeMode.fromValue(getSharedPreferences("gestor_domestico_settings", MODE_PRIVATE).getString("theme_mode", null))
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
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { LinkScreen(model) { finish() } }
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.pause()
        super.onStop()
    }
}
data class LinkUiState(
    val busy: Boolean = false, val addresses: List<String> = emptyList(), val deviceId: String = "",
    val endpoint: LinkEndpoint? = null, val invitation: String = "", val disk: LinkDiskState = LinkDiskState(),
    val sharing: ShoppingExchange.Status = ShoppingExchange.Status("LOCAL", false, false),
    val message: String = "Todavia no hay un enlace comprobado."
)
class LinkSession(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(LinkUiState())
        private set
    private val store = AndroidLinkStore(application)
    private val sharing by lazy {
        ShoppingExchange(RoomExchangeDatabase((getApplication<Application>() as ShoppingApplication).database))
    }
    private val lock = Any()
    private var generation = 0L
    private var server: LanLinkServer? = null
    private var gate: PairingGate? = null
    private var call: LinkCall? = null
    private var job: Job? = null
    init { refresh() }
    private suspend fun identity(): String = (getApplication<Application>() as ShoppingApplication).database.syncJournalDao().deviceId()
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
                val latest = withContext(Dispatchers.IO) { store.state() to sharing.status() }
                state = state.copy(disk = latest.first, sharing = latest.second, message = message)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                val saved = try { withContext(Dispatchers.IO) { store.state() to sharing.status() } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                state = state.copy(disk = saved?.first ?: state.disk, sharing = saved?.second ?: state.sharing, message =
                    "Operacion no confirmada. Revisa la Wi-Fi, el servidor y los limites de esta prueba. " +
                    "Los envios pendientes se conservan. Un servidor pudo guardar cambios aunque se perdiera su respuesta.")
            } finally {
                if (synchronized(lock) { generation == current }) state = state.copy(busy = false)
            }
        }
    }
    fun refresh() = action {
        val snapshot = withContext(Dispatchers.IO) { Triple(localAddresses(), identity(), store.state()) }
        state = state.copy(addresses = snapshot.first, deviceId = snapshot.second, disk = snapshot.third)
        "Direcciones, vinculacion y estado de compras cargados."
    }
    fun start(host: String) = action { current ->
        val endpoint = withContext(Dispatchers.IO) {
            check(store.state().client == null)
            require(host in localAddresses())
            val deviceId = identity()
            // The UI explicitly asks to share ALL lists before calling this action.
            sharing.enableHost()
            val security = AndroidLinkTls.server()
            val nextGate = PairingGate(deviceId, store)
            val nextServer = LanLinkServer(security.first, nextGate, security.second, onPaired = {
                viewModelScope.launch {
                    if (synchronized(lock) { generation == current }) {
                        try {
                            val disk = withContext(Dispatchers.IO) { store.state() }
                            state = state.copy(disk = disk, invitation = "", message = "Cliente autorizado. Puede iniciar el intercambio manual.")
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { state = state.copy(message = "No se pudo leer el estado del enlace.") }
                    }
                }
            }, onExchange = { peer, payload ->
                val response = sharing.receive(peer, ExchangeCodec.request(payload))
                val bytes = ExchangeCodec.replyBytes(response)
                viewModelScope.launch {
                    if (synchronized(lock) { generation == current }) state = state.copy(message =
                        if (response.status == ReplyStatus.APPLIED) "Intercambio guardado. La recepcion en el cliente debe confirmarse alli."
                        else "Conflicto detectado: no se aplicaron los cambios de ese lote en el servidor.")
                }
                bytes
            })
            val result = nextServer.start(host)
            synchronized(lock) {
                if (generation != current || server != null) { nextServer.close(); throw CancellationException() }
                server = nextServer; gate = nextGate
            }
            result
        }
        state = state.copy(endpoint = endpoint)
        "Servidor de compras iniciado. Se detiene al salir de esta pantalla o mandar la app al fondo."
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
            check(existing.peer == null && existing.client == null && sharing.status().role != "HOST")
            check(synchronized(lock) { server == null })
            val invitation = LinkInvitation.parse(text)
            val client = ClientLink(invitation.endpoint, identity(), LinkSecrets.newToken())
            require(client.deviceId != client.endpoint.serverId)
            val connection = registerCall(current)
            try {
                store.saveClient(client)
                val negotiated = LanLinkClient.pair(invitation, client, connection)
                store.saveClient(client.copy(confirmed = true))
                negotiated
            } finally { connection.close() }
        }
        "Vinculacion confirmada mediante $protocol. Aun no se ha solicitado compartir las compras de este cliente."
    }
    fun probe() = action { current ->
        val protocol = withContext(Dispatchers.IO) {
            val client = store.state().client ?: error("No hay cliente guardado")
            require(client.deviceId == identity())
            val connection = registerCall(current)
            try {
                val negotiated = LanLinkClient.probe(client, connection)
                store.saveClient(client.copy(confirmed = true)); negotiated
            } finally { connection.close() }
        }
        "Enlace comprobado: $protocol. Comprobar el enlace no envia compras."
    }
    fun syncPurchases() = action { current ->
        val result = withContext(Dispatchers.IO) {
            val client = store.state().client ?: error("No hay servidor guardado")
            require(client.deviceId == identity())
            val request = sharing.prepare(client.endpoint.serverId)
            val connection = registerCall(current)
            try {
                val response = LanLinkClient.exchangePurchases(client, ExchangeCodec.requestBytes(request), connection)
                sharing.accept(ExchangeCodec.reply(response))
            } finally { connection.close() }
        }
        when {
            result.conflict -> "Conflicto pendiente de revision. Se conservan las compras locales y el lote. No se elegira una version automaticamente."
            result.localChangesRemain -> "Intercambio confirmado. Hay cambios nuevos locales para el siguiente envio."
            else -> "Intercambio confirmado en ambos extremos para este lote."
        }
    }
    fun forget() {
        pause()
        action { withContext(Dispatchers.IO) { store.forget() }; "Credencial eliminada aqui. El grupo, compras y envios pendientes NO se borran." }
    }
    fun pause() {
        synchronized(lock) {
            generation++
            server?.close(); server = null; gate = null
            call?.close(); call = null
        }
        job?.cancel(); job = null
        state = state.copy(busy = false, endpoint = null, invitation = "", message =
            "Conexion detenida. El estado de los envios se conserva; no hay servidor en segundo plano.")
    }
    override fun onCleared() { pause(); super.onCleared() }
}
@Composable
private fun LinkScreen(model: LinkSession, onBack: () -> Unit) {
    val state = model.state
    var address by rememberSaveable { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    var confirmBootstrap by remember { mutableStateOf(false) }
    LaunchedEffect(state.addresses) { if (address !in state.addresses) address = state.addresses.firstOrNull().orEmpty() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Volver a compras") }
            Text("Dispositivos - compras LAN", style = MaterialTheme.typography.headlineSmall)
            Text("Intercambio manual experimental. Usa solo datos de prueba hasta validar dos moviles.", color = MaterialTheme.colorScheme.primary)
            Text("Ambos moviles en la misma Wi-Fi y con esta pantalla abierta. No hay servidor permanente.")
            if (state.deviceId.isNotEmpty()) Text("Este dispositivo: ${state.deviceId.takeLast(8)}")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message)
            if (state.sharing.pending) Text("Hay un envio guardado pendiente de completar.")
            if (state.sharing.conflict) Text("Sincronizacion bloqueada por conflicto. La pantalla para elegir versiones sigue pendiente; no borres la app ni sus datos.",
                color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            if (state.disk.client == null && state.sharing.role != "CLIENT") {
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
                    Button(enabled = !state.busy && address.isNotEmpty(), onClick = { confirmShare = true }) { Text("Compartir como servidor") }
                } else {
                    Text("Escuchando en ${state.endpoint.host}:${state.endpoint.port}")
                    OutlinedButton(onClick = model::pause) { Text("Detener servidor") }
                    if (state.disk.peer == null) Button(enabled = !state.busy, onClick = model::invite) { Text("Crear invitacion") }
                }
                if (state.invitation.isNotEmpty()) {
                    Text("SECRETO: no publicar ni enviar a terceros. Copia la invitacion al otro movil.", color = MaterialTheme.colorScheme.error)
                    SelectionContainer { Text(state.invitation, style = MaterialTheme.typography.bodySmall) }
                }
                state.disk.peer?.let { Text("Cliente autorizado: ${it.deviceId.takeLast(8)}") }
            }
            if (state.endpoint == null && state.disk.peer == null && state.sharing.role != "HOST") {
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
                    Text(if (client.confirmed) "Vinculado; el servidor puede estar desconectado ahora." else "Intento guardado; usa Comprobar enlace.")
                    Button(enabled = !state.busy, onClick = model::probe) { Text("Comprobar enlace") }
                    Button(enabled = !state.busy && !state.sharing.conflict, onClick = {
                        if (state.sharing.role == "LOCAL") confirmBootstrap = true else model.syncPurchases()
                    }) { Text(if (state.sharing.pending) "Reintentar envio pendiente" else "Sincronizar compras ahora") }
                    if (state.sharing.role == "LOCAL") Text("Primera sincronizacion: este cliente debe estar vacio. No se mezclan ni borran compras existentes automaticamente.")
                }
            }
            if (state.disk.peer != null || state.disk.client != null) {
                OutlinedButton(enabled = !state.busy, onClick = { confirmForget = true }) {
                    Text(if (state.disk.peer != null) "Revocar cliente y detener" else "Olvidar servidor")
                }
            }
            Text("Se comparten todas las listas, incluidas las archivadas. Limite experimental: 2000 registros con borrados, 2 MiB por mensaje. No abras puertos del router.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmShare) AlertDialog(
        onDismissRequest = { confirmShare = false }, title = { Text("Compartir todas las compras") },
        text = { Text("El cliente autorizado podra ver y modificar todas las listas, incluidas las archivadas. Se creara un grupo si aun no existe. La restauracion de JSON quedara bloqueada mientras esta base pertenezca al grupo.") },
        confirmButton = { TextButton(onClick = { confirmShare = false; model.start(address) }) { Text("Compartir") } },
        dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Cancelar") } }
    )
    if (confirmBootstrap) AlertDialog(
        onDismissRequest = { confirmBootstrap = false }, title = { Text("Recibir las compras del servidor") },
        text = { Text("Se iniciara un grupo compartido. Este cliente debe estar vacio; si ya tiene compras, la operacion se detendra sin borrarlas. No desinstales la app para vaciarla.") },
        confirmButton = { TextButton(onClick = { confirmBootstrap = false; model.syncPurchases() }) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = { confirmBootstrap = false }) { Text("Cancelar") } }
    )
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false }, title = { Text("Eliminar vinculacion") },
        text = { Text("Se elimina la credencial de este movil. No se borran compras, grupos ni envios pendientes. Para revocar el acceso del cliente, hazlo en el servidor. Cambiar de grupo aun no esta implementado.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; model.forget() }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } }
    )
}
