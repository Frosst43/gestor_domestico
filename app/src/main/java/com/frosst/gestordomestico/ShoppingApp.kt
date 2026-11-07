package com.frosst.gestordomestico

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

private val purchasedGreen = Color(0xFF16803C)
private fun uniqueId(): String = UUID.randomUUID().toString()
private enum class EditKind { LIST, CATEGORY, PRODUCT }

private data class EditRequest(
    val kind: EditKind,
    val id: String? = null,
    val name: String = "",
    val quantity: String = "",
    val notes: String = ""
)

private data class ConfirmRequest(
    val title: String,
    val message: String,
    val action: () -> Unit
)

@Composable
fun ShoppingApp(dao: ShoppingDao) {
    val scope = rememberCoroutineScope()
    val lists by dao.observeLists().collectAsState(initial = emptyList())
    val categories by dao.observeCategories().collectAsState(initial = emptyList())
    val products by dao.observeProducts().collectAsState(initial = emptyList())

    var listId by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    fun back() {
        when {
            categoryId != null -> categoryId = null
            listId != null -> listId = null
            archived -> archived = false
        }
    }

    BackHandler(enabled = categoryId != null || listId != null || archived) { back() }

    val selectedList = lists.firstOrNull { it.id == listId }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val title = selectedCategory?.name ?: selectedList?.name ?: if (archived) "Archivo" else "Mis listas"

    val productsForList: (String) -> List<Product> = { id ->
        val categoryIds = categories.filter { it.listId == id }.map { it.id }.toSet()
        products.filter { it.categoryId in categoryIds }
    }

    fun requestDeletion(name: String, explanation: String, action: () -> Unit) {
        confirm = ConfirmRequest("Eliminar $name", explanation, action)
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (listId != null || archived) {
                        TextButton(onClick = { back() }) { Text("‹ Volver") }
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        floatingActionButton = {
            if (!archived || listId != null) {
                FloatingActionButton(onClick = {
                    edit = when {
                        categoryId != null -> EditRequest(EditKind.PRODUCT)
                        listId != null -> EditRequest(EditKind.CATEGORY)
                        else -> EditRequest(EditKind.LIST)
                    }
                }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)
        ) {
            when {
                listId == null -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { archived = false }) { Text("Listas activas") }
                        TextButton(onClick = { archived = true }) { Text("Archivadas") }
                    }
                    val visible = lists.filter { it.archived == archived }
                    if (visible.isEmpty()) {
                        EmptyState(if (archived) "No tienes listas archivadas." else "No tienes listas. Pulsa + para crear una.")
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visible, key = { it.id }) { shoppingList ->
                            val listProducts = productsForList(shoppingList.id)
                            val progress = progressOf(listProducts)
                            ShoppingListCard(
                                list = shoppingList,
                                progress = progress,
                                onOpen = {
                                    listId = shoppingList.id
                                    categoryId = null
                                },
                                onRename = { edit = EditRequest(EditKind.LIST, shoppingList.id, shoppingList.name) },
                                onDuplicate = { scope.launch {
                                    dao.duplicateList(shoppingList, ::uniqueId)
                                    archived = false
                                } },
                                onArchive = { scope.launch {
                                    dao.archiveList(shoppingList.id, !shoppingList.archived)
                                } },
                                onReset = {
                                    confirm = ConfirmRequest(
                                        "Reutilizar lista",
                                        "Todos los productos de «${shoppingList.name}» volverán a estar pendientes.",
                                        { scope.launch { dao.resetList(shoppingList.id) } }
                                    )
                                },
                                onClear = {
                                    confirm = ConfirmRequest(
                                        "Vaciar comprados",
                                        "Se eliminarán únicamente los productos comprados de «${shoppingList.name}».",
                                        { scope.launch { dao.clearPurchased(shoppingList.id) } }
                                    )
                                },
                                onDelete = {
                                    requestDeletion(
                                        "lista",
                                        "Se borrarán «${shoppingList.name}», sus categorías y todos sus productos. No se puede deshacer."
                                    ) { scope.launch { dao.deleteList(shoppingList.id) } }
                                }
                            )
                        }
                    }
                }
                categoryId == null -> {
                    val visible = categories.filter { it.listId == listId }
                    if (visible.isEmpty()) EmptyState("No hay categorías. Pulsa + para añadir una.")
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visible, key = { it.id }) { category ->
                            val categoryProducts = products.filter { it.categoryId == category.id }
                            val progress = progressOf(categoryProducts)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        Modifier.weight(1f).clickable { categoryId = category.id }.padding(vertical = 6.dp)
                                    ) {
                                        Text(category.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${progress.purchased} de ${progress.total} comprados", style = MaterialTheme.typography.bodySmall)
                                    }
                                    MoreMenu(
                                        items = listOf(
                                            "Editar" to { edit = EditRequest(EditKind.CATEGORY, category.id, category.name) },
                                            "Eliminar" to {
                                                requestDeletion(
                                                    "categoría",
                                                    "Se eliminará «${category.name}» junto con todos sus productos."
                                                ) { scope.launch { dao.deleteCategory(category.id) } }
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    val filtered = purchaseOrder(products.filter { it.categoryId == categoryId })
                    val pending = filtered.filterNot { it.purchased }
                    val purchased = filtered.filter { it.purchased }
                    if (filtered.isEmpty()) EmptyState("No hay productos. Pulsa + para añadir uno.")
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item { SectionTitle("Pendientes (${pending.size})") }
                        items(pending, key = { it.id }) { product ->
                            ProductCard(
                                product = product,
                                onToggle = { scope.launch { dao.markProduct(product.id, !product.purchased) } },
                                onEdit = { edit = EditRequest(EditKind.PRODUCT, product.id, product.name, product.quantity, product.notes) },
                                onDelete = {
                                    requestDeletion("producto", "Se eliminará «${product.name}» de esta lista.") {
                                        scope.launch { dao.deleteProduct(product.id) }
                                    }
                                }
                            )
                        }
                        item { SectionTitle("✓ Comprados (${purchased.size})", purchasedGreen) }
                        items(purchased, key = { it.id }) { product ->
                            ProductCard(
                                product = product,
                                onToggle = { scope.launch { dao.markProduct(product.id, !product.purchased) } },
                                onEdit = { edit = EditRequest(EditKind.PRODUCT, product.id, product.name, product.quantity, product.notes) },
                                onDelete = {
                                    requestDeletion("producto", "Se eliminará «${product.name}» de esta lista.") {
                                        scope.launch { dao.deleteProduct(product.id) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val currentEdit = edit
    if (currentEdit != null) {
        EditDialog(
            request = currentEdit,
            onDismiss = { edit = null },
            onSave = { name, quantity, notes ->
                scope.launch {
                    when (currentEdit.kind) {
                        EditKind.LIST -> {
                            if (currentEdit.id == null) {
                                dao.addList(ShoppingList(uniqueId(), name))
                                archived = false
                            } else dao.renameList(currentEdit.id, name)
                        }
                        EditKind.CATEGORY -> {
                            if (currentEdit.id == null) {
                                listId?.let { dao.addCategory(Category(uniqueId(), it, name)) }
                            } else dao.renameCategory(currentEdit.id, name)
                        }
                        EditKind.PRODUCT -> {
                            if (currentEdit.id == null) {
                                categoryId?.let { dao.addProduct(Product(uniqueId(), it, name, quantity, notes)) }
                            } else dao.editProduct(currentEdit.id, name, quantity, notes)
                        }
                    }
                }
                edit = null
            }
        )
    }

    val pendingConfirm = confirm
    if (pendingConfirm != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pendingConfirm.title) },
            text = { Text(pendingConfirm.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    pendingConfirm.action()
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(message, modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(title: String, color: Color = Color.Unspecified) {
    Text(
        title,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = color
    )
}

@Composable
private fun ShoppingListCard(
    list: ShoppingList,
    progress: ShoppingProgress,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onReset: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onOpen() }.padding(vertical = 8.dp)) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    Text("${progress.purchased} de ${progress.total} comprados", style = MaterialTheme.typography.bodySmall)
                }
                MoreMenu(
                    items = listOf(
                        "Cambiar nombre" to onRename,
                        "Duplicar lista" to onDuplicate,
                        (if (list.archived) "Recuperar lista" else "Archivar lista") to onArchive,
                        "Reutilizar lista" to onReset,
                        "Vaciar comprados" to onClear,
                        "Eliminar lista" to onDelete
                    )
                )
            }
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
            if (progress.completed) Text("✓ Compra completada", color = purchasedGreen)
        }
    }
}

@Composable
private fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (name, action) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        action()
                    }
                )
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: Product,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = product.purchased,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = purchasedGreen)
            )
            Column(Modifier.weight(1f).clickable { onToggle() }.padding(vertical = 6.dp)) {
                Text(
                    product.name,
                    textDecoration = if (product.purchased) TextDecoration.LineThrough else null,
                    color = if (product.purchased) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (product.quantity.isNotBlank()) {
                    Text(
                        product.quantity,
                        textDecoration = if (product.purchased) TextDecoration.LineThrough else null,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (product.notes.isNotBlank()) {
                    Text(product.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (product.purchased) Text("✓", color = purchasedGreen)
            MoreMenu(listOf("Editar" to onEdit, "Eliminar" to onDelete))
        }
    }
}

@Composable
private fun EditDialog(
    request: EditRequest,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember(request) { mutableStateOf(request.name) }
    var quantity by remember(request) { mutableStateOf(request.quantity) }
    var notes by remember(request) { mutableStateOf(request.notes) }
    val kindName = when (request.kind) {
        EditKind.LIST -> "lista"
        EditKind.CATEGORY -> "categoría"
        EditKind.PRODUCT -> "producto"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.id == null) "Nuevo $kindName" else "Editar $kindName") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
                if (request.kind == EditKind.PRODUCT) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Cantidad y unidad (opcional)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notas (opcional)") },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), quantity.trim(), notes.trim()) }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
