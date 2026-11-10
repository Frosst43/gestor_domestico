package com.frosst.gestordomestico

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val purchasedGreen = Color(0xFF16803C)
private fun uniqueId(): String = UUID.randomUUID().toString()
private data class ConfirmRequest(val title: String, val message: String, val action: () -> Unit)

@Composable
fun ShoppingApp(
    dao: ShoppingDao,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    session: ShoppingSession
) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val snackbar = remember { SnackbarHostState() }
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }
    var themeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var listId by session.listIdState
    var categoryId by session.categoryIdState
    var archived by session.archivedState
    var searchQuery by session.searchState
    var edit by session.editState
    var quick by session.quickState
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    val notice = session.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            snackbar.showSnackbar(notice.text)
            session.consumeNotice(notice)
        }
    }
    // Preserve the current search on recreation; clear only on actual navigation.
    var previousLocation by remember { mutableStateOf(Triple(listId, categoryId, archived)) }
    LaunchedEffect(listId, categoryId, archived) {
        val location = Triple(listId, categoryId, archived)
        if (location != previousLocation) searchQuery = ""
        previousLocation = location
    }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Encoding also runs off the main thread; validate before opening the output.
                    val bytes = BackupCodec.encode(dao.exportBackup()).toByteArray(Charsets.UTF_8)
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("No se puede abrir el destino")
                }
                snackbar.showSnackbar("Copia de seguridad guardada")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { snackbar.showSnackbar("No se pudo guardar la copia: ${error.message}") }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                session.pendingRestore = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { BackupCodec.decode(BackupCodec.readLimited(it)) }
                        ?: throw IllegalStateException("No se puede abrir el archivo")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { snackbar.showSnackbar("Copia no valida: ${error.message}") }
        }
    }

    val lists by dao.observeLists().collectAsState(initial = emptyList())
    val categories by dao.observeCategories().collectAsState(initial = emptyList())
    val products by dao.observeProducts().collectAsState(initial = emptyList())
    val selectedList = lists.firstOrNull { it.id == listId }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val title = selectedCategory?.name ?: selectedList?.name ?: if (archived) "Archivo" else "Mis listas"

    fun back() {
        if (session.restoring) return
        when {
            categoryId != null -> categoryId = null
            listId != null -> listId = null
            archived -> archived = false
        }
    }
    BackHandler(enabled = categoryId != null || listId != null || archived) { back() }
    fun productsForList(id: String): List<Product> {
        val ids = categories.filter { it.listId == id }.map { it.id }.toSet()
        return products.filter { it.categoryId in ids }
    }
    fun requestDeletion(name: String, explanation: String, action: () -> Unit) {
        confirm = ConfirmRequest("Eliminar $name", explanation, action)
    }
    fun openQuickAdd(targetListId: String) {
        val first = categories.firstOrNull { it.listId == targetListId }
        if (first == null) {
            listId = targetListId
            categoryId = null
            edit = EditRequest(EditKind.CATEGORY)
        } else quick = QuickDraft(targetListId, first.id)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding(), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (listId != null || archived) TextButton(onClick = { back() }) { Text("‹ Volver") }
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (listId == null) TextButton(onClick = { showBackupDialog = true }) { Text("Copia") }
                    TextButton(onClick = { themeDialogVisible = true }) { Text("Tema") }
                }
            }
        },
        floatingActionButton = {
            if (!archived || listId != null) FloatingActionButton(onClick = {
                edit = when {
                    categoryId != null -> EditRequest(EditKind.PRODUCT)
                    listId != null -> EditRequest(EditKind.CATEGORY)
                    else -> EditRequest(EditKind.LIST)
                }
            }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text(when { categoryId != null -> "Buscar productos"; listId != null -> "Buscar categorias"; else -> "Buscar listas" }) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)
            )
            when {
                listId == null -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { archived = false }) { Text("Listas activas") }
                        TextButton(onClick = { archived = true }) { Text("Archivadas") }
                    }
                    val visible = lists.filter { it.archived == archived && it.name.contains(searchQuery, ignoreCase = true) }
                    if (visible.isEmpty()) EmptyState(if (searchQuery.isNotBlank()) "Ninguna lista coincide con la busqueda." else if (archived) "No tienes listas archivadas." else "No tienes listas. Pulsa + para crear una.")
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.id }) { item ->
                            ShoppingListCard(
                                list = item, progress = progressOf(productsForList(item.id)),
                                onOpen = { listId = item.id; categoryId = null },
                                onQuickAdd = { openQuickAdd(item.id) },
                                onRename = { edit = EditRequest(EditKind.LIST, item.id, item.name) },
                                onDuplicate = { session.operation { dao.duplicateList(item, ::uniqueId); archived = false } },
                                onArchive = { session.operation { dao.archiveList(item.id, !item.archived) } },
                                onReset = { confirm = ConfirmRequest("Reutilizar lista", "Todos los productos de «${item.name}» volveran a estar pendientes.") { session.operation { dao.resetList(item.id) } } },
                                onClear = { confirm = ConfirmRequest("Vaciar comprados", "Se eliminaran solo los productos comprados de «${item.name}».") { session.operation { dao.clearPurchased(item.id) } } },
                                onDelete = { requestDeletion("lista", "Se borraran «${item.name}», sus categorias y sus productos. No se puede deshacer.") { session.operation { dao.deleteList(item.id) } } }
                            )
                        }
                    }
                }
                categoryId == null -> {
                    if (selectedList?.archived == false && categories.any { it.listId == listId }) {
                        OutlinedButton(onClick = { listId?.let { openQuickAdd(it) } }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("+ Añadir producto") }
                    }
                    val visible = categories.filter { it.listId == listId && it.name.contains(searchQuery, ignoreCase = true) }
                    if (visible.isEmpty()) EmptyState(if (searchQuery.isNotBlank()) "Ninguna categoria coincide con la busqueda." else "No hay categorias. Pulsa + para añadir una.")
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.id }) { cat ->
                            val progress = progressOf(products.filter { it.categoryId == cat.id })
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { categoryId = cat.id }.padding(vertical = 6.dp)) {
                                        Text(cat.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${progress.purchased} de ${progress.total} comprados", style = MaterialTheme.typography.bodySmall)
                                    }
                                    MoreMenu(listOf(
                                        "Editar" to { edit = EditRequest(EditKind.CATEGORY, cat.id, cat.name) },
                                        "Eliminar" to { requestDeletion("categoria", "Se eliminara «${cat.name}» y todos sus productos.") { session.operation { dao.deleteCategory(cat.id) } } }
                                    ))
                                }
                            }
                        }
                    }
                }
                else -> {
                    val filtered = purchaseOrder(products.filter { it.categoryId == categoryId && (it.name.contains(searchQuery, true) || it.notes.contains(searchQuery, true)) })
                    val pending = filtered.filterNot { it.purchased }
                    val purchased = filtered.filter { it.purchased }
                    if (filtered.isEmpty()) EmptyState(if (searchQuery.isNotBlank()) "Ningun producto coincide con la busqueda." else "No hay productos. Pulsa + para añadir uno.")
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { SectionTitle("Pendientes (${pending.size})") }
                        items(pending, key = { it.id }) { product ->
                            ProductCard(product,
                                onToggle = { session.operation { dao.markProduct(product.id, !product.purchased) } },
                                onEdit = { edit = EditRequest(EditKind.PRODUCT, product.id, product.name, product.quantity, product.notes) },
                                onDelete = { requestDeletion("producto", "Se eliminara «${product.name}» de esta lista.") { session.operation { dao.deleteProduct(product.id) } } })
                        }
                        item { SectionTitle("✓ Comprados (${purchased.size})", purchasedGreen) }
                        items(purchased, key = { it.id }) { product ->
                            ProductCard(product,
                                onToggle = { session.operation { dao.markProduct(product.id, !product.purchased) } },
                                onEdit = { edit = EditRequest(EditKind.PRODUCT, product.id, product.name, product.quantity, product.notes) },
                                onDelete = { requestDeletion("producto", "Se eliminara «${product.name}» de esta lista.") { session.operation { dao.deleteProduct(product.id) } } })
                        }
                    }
                }
            }
        }
    }

    val draft = quick
    val quickTarget = lists.firstOrNull { it.id == draft?.listId && !it.archived }
    if (draft != null && quickTarget != null) {
        val choices = categories.filter { it.listId == draft.listId }
        QuickAddDialog(quickTarget.name, draft, choices,
            onDismiss = { if (!draft.saving) quick = null },
            onSave = { session.saveQuick(dao, choices.any { it.id == draft.categoryId }) })
    }
    if (showBackupDialog) AlertDialog(
        onDismissRequest = { showBackupDialog = false }, title = { Text("Copia de seguridad") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Guarda o recupera todas las listas, categorias y productos.")
            Text("El archivo JSON no esta cifrado. Guardalo en un lugar privado.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(), onClick = { showBackupDialog = false; saveBackup.launch("gestor-domestico-copia.json") }) { Text("Guardar copia") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { showBackupDialog = false; openBackup.launch(arrayOf("application/json")) }) { Text("Restaurar desde archivo") }
        } },
        confirmButton = { TextButton(onClick = { showBackupDialog = false }) { Text("Cerrar") } }
    )
    val backup = session.pendingRestore
    if (backup != null) AlertDialog(
        onDismissRequest = { if (!session.restoring) session.pendingRestore = null },
        title = { Text("Confirmar restauracion") },
        text = { Text("La copia contiene ${backup.lists.size} listas, ${backup.categories.size} categorias y ${backup.products.size} productos. Restaurar sustituira TODOS los datos actuales del movil. Guarda antes una copia si quieres conservarlos.") },
        confirmButton = { TextButton(enabled = !session.restoring, onClick = { session.restore(dao, backup) }) { Text(if (session.restoring) "Restaurando..." else "Restaurar") } },
        dismissButton = { TextButton(enabled = !session.restoring, onClick = { session.pendingRestore = null }) { Text("Cancelar") } }
    )
    if (themeDialogVisible) AlertDialog(
        onDismissRequest = { themeDialogVisible = false }, title = { Text("Apariencia") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { onThemeModeChanged(mode); themeDialogVisible = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = themeMode == mode, onClick = { onThemeModeChanged(mode); themeDialogVisible = false })
                    Spacer(Modifier.width(8.dp)); Text(mode.label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { themeDialogVisible = false }) { Text("Cerrar") } }
    )
    val currentEdit = edit
    if (currentEdit != null) EditDialog(currentEdit,
        onDismiss = { if (!currentEdit.saving) edit = null },
        onSave = { session.saveEdit(dao) })
    val pendingConfirm = confirm
    if (pendingConfirm != null) AlertDialog(
        onDismissRequest = { confirm = null }, title = { Text(pendingConfirm.title) }, text = { Text(pendingConfirm.message) },
        confirmButton = { TextButton(onClick = { confirm = null; pendingConfirm.action() }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } }
    )
}

@Composable
private fun EmptyState(message: String) {
    Text(message, modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(title: String, color: Color = Color.Unspecified) {
    Text(title, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun ShoppingListCard(
    list: ShoppingList, progress: ShoppingProgress, onOpen: () -> Unit,
    onQuickAdd: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit,
    onArchive: () -> Unit, onReset: () -> Unit, onClear: () -> Unit, onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onOpen() }.padding(vertical = 8.dp)) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    Text("${progress.purchased} de ${progress.total} comprados", style = MaterialTheme.typography.bodySmall)
                }
                MoreMenu(listOf("Cambiar nombre" to onRename, "Duplicar lista" to onDuplicate,
                    (if (list.archived) "Recuperar lista" else "Archivar lista") to onArchive,
                    "Reutilizar lista" to onReset, "Vaciar comprados" to onClear, "Eliminar lista" to onDelete))
            }
            if (progress.total > 0) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (!list.archived) TextButton(onClick = onQuickAdd) { Text("+ Añadir producto") }
            if (progress.completed) {
                Text("✓ Compra completada", color = purchasedGreen)
                if (!list.archived) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onReset) { Text("Reutilizar") }
                    TextButton(onClick = onArchive) { Text("Archivar") }
                    TextButton(onClick = onDelete) { Text("Eliminar") }
                }
            }
        }
    }
}

@Composable
private fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (name, action) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; action() }) }
        }
    }
}

@Composable
private fun ProductCard(product: Product, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = product.purchased, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = purchasedGreen))
            Column(Modifier.weight(1f).clickable { onToggle() }.padding(vertical = 6.dp)) {
                Text(product.name, textDecoration = if (product.purchased) TextDecoration.LineThrough else null,
                    color = if (product.purchased) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (product.quantity.isNotBlank()) Text(product.quantity, textDecoration = if (product.purchased) TextDecoration.LineThrough else null,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (product.notes.isNotBlank()) Text(product.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (product.purchased) Text("✓", color = purchasedGreen)
            MoreMenu(listOf("Editar" to onEdit, "Eliminar" to onDelete))
        }
    }
}

@Composable
private fun EditDialog(request: EditRequest, onDismiss: () -> Unit, onSave: () -> Unit) {
    val kind = when (request.kind) { EditKind.LIST -> "lista"; EditKind.CATEGORY -> "categoria"; EditKind.PRODUCT -> "producto" }
    AlertDialog(
        onDismissRequest = { if (!request.saving) onDismiss() },
        title = { Text(if (request.id == null) "Crear $kind" else "Editar $kind") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = request.name, onValueChange = { request.name = it }, enabled = !request.saving, label = { Text("Nombre") }, singleLine = true)
            if (request.kind == EditKind.PRODUCT) {
                OutlinedTextField(value = request.quantity, onValueChange = { request.quantity = it }, enabled = !request.saving, label = { Text("Cantidad y unidad (opcional)") }, singleLine = true)
                OutlinedTextField(value = request.notes, onValueChange = { request.notes = it }, enabled = !request.saving, label = { Text("Notas (opcional)") }, minLines = 2, maxLines = 4)
            }
            request.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = request.name.isNotBlank() && !request.saving, onClick = onSave) { Text(if (request.saving) "Guardando..." else "Guardar") } },
        dismissButton = { TextButton(enabled = !request.saving, onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun QuickAddDialog(listName: String, draft: QuickDraft, categories: List<Category>, onDismiss: () -> Unit, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == draft.categoryId }
    AlertDialog(
        onDismissRequest = { if (!draft.saving) onDismiss() }, title = { Text("Añadir a «$listName»") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(enabled = !draft.saving, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Categoria: ${selected?.name ?: "Seleccionar"} ▾") }
                DropdownMenu(expanded = expanded && !draft.saving, onDismissRequest = { expanded = false }) {
                    categories.forEach { cat -> DropdownMenuItem(text = { Text(cat.name) }, onClick = { draft.categoryId = cat.id; expanded = false }) }
                }
            }
            OutlinedTextField(value = draft.name, onValueChange = { draft.name = it }, enabled = !draft.saving, label = { Text("Producto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.quantity, onValueChange = { draft.quantity = it }, enabled = !draft.saving, label = { Text("Cantidad y unidad (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.notes, onValueChange = { draft.notes = it }, enabled = !draft.saving, label = { Text("Notas (opcional)") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.keepAdding, enabled = !draft.saving, onCheckedChange = { draft.keepAdding = it }); Text("Seguir añadiendo")
            }
            if (draft.savedCount > 0) Text("Guardados: ${draft.savedCount}", style = MaterialTheme.typography.bodySmall)
            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(enabled = draft.name.isNotBlank() && selected != null && !draft.saving, onClick = onSave) { Text(if (draft.saving) "Guardando..." else "Añadir") } },
        dismissButton = { TextButton(enabled = !draft.saving, onClick = onDismiss) { Text("Cerrar") } }
    )
}
