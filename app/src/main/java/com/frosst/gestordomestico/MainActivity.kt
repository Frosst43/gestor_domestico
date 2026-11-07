package com.frosst.gestordomestico

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.launch
import java.util.UUID

private fun newId(): String = UUID.randomUUID().toString()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, ShoppingDatabase::class.java, "shopping.db")
            .build()
        setContent {
            MaterialTheme { ShoppingApp(db.shoppingDao()) }
        }
    }
}

@Composable
private fun ShoppingApp(dao: ShoppingDao) {
    val scope = rememberCoroutineScope()
    val lists by dao.observeLists().collectAsState(initial = emptyList())
    val categories by dao.observeCategories().collectAsState(initial = emptyList())
    val products by dao.observeProducts().collectAsState(initial = emptyList())
    var listId by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var showArchive by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf<String?>(null) }
    var textValue by remember { mutableStateOf("") }
    var detailValue by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun prompt(title: String) {
        textValue = ""
        detailValue = ""
        dialogTitle = title
    }
    fun confirm(action: () -> Unit) { pendingAction = action }
    fun listProducts(id: String) = products.filter { product ->
        categories.any { it.id == product.categoryId && it.listId == id }
    }

    val selectedList = lists.find { it.id == listId }
    val selectedCategory = categories.find { it.id == categoryId }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (listId != null) {
                        TextButton(onClick = { if (categoryId != null) categoryId = null else listId = null }) { Text("‹ Volver") }
                    }
                    Text(selectedCategory?.name ?: selectedList?.name ?: if (showArchive) "Archivo" else "Mis listas", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                prompt(when {
                    categoryId != null -> "Nuevo producto"
                    listId != null -> "Nueva categoría"
                    else -> "Nueva lista"
                })
            }) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (listId == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showArchive = false }) { Text("Activas") }
                    TextButton(onClick = { showArchive = true }) { Text("Archivadas") }
                }
                val shown = lists.filter { it.archived == showArchive }
                if (shown.isEmpty()) Text("No hay listas todavía. Pulsa + para crear una.", modifier = Modifier.padding(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { list ->
                        val all = listProducts(list.id)
                        val done = all.count { it.purchased }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable {
                                    listId = list.id
                                    categoryId = null
                                })
                                Text("$done de ${all.size} comprados")
                                if (all.isNotEmpty()) LinearProgressIndicator(
                                    progress = { done.toFloat() / all.size.toFloat() },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                                if (all.isNotEmpty() && done == all.size) Text("Compra completada ✓", color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { scope.launch { dao.duplicateList(list, ::newId) } }) { Text("Duplicar") }
                                    TextButton(onClick = { scope.launch { dao.archiveList(list.id, !list.archived) } }) { Text(if (list.archived) "Recuperar" else "Archivar") }
                                    TextButton(onClick = { confirm { scope.launch { dao.deleteList(list.id) } } }) { Text("Eliminar") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { confirm { scope.launch { dao.clearPurchased(list.id) } } }) { Text("Vaciar comprados") }
                                    TextButton(onClick = { confirm { scope.launch { dao.resetList(list.id) } } }) { Text("Reutilizar") }
                                }
                            }
                        }
                    }
                }
            } else if (categoryId == null) {
                val current = categories.filter { it.listId == listId }
                if (current.isEmpty()) Text("Crea una categoría pulsando +.", modifier = Modifier.padding(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current, key = { it.id }) { cat ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { categoryId = cat.id }) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, modifier = Modifier.weight(1f))
                                TextButton(onClick = { confirm { scope.launch { dao.deleteCategory(cat.id) } } }) { Text("Eliminar") }
                            }
                        }
                    }
                }
            } else {
                val current = products.filter { it.categoryId == categoryId }.sortedWith(compareBy<Product> { it.purchased }.thenBy { it.name.lowercase() })
                val pending = current.filterNot { it.purchased }
                val bought = current.filter { it.purchased }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text("Pendientes (${pending.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(pending, key = { it.id }) { item ->
                        ProductRow(item, onToggle = { scope.launch { dao.markProduct(item.id, true) } }, onDelete = { confirm { scope.launch { dao.deleteProduct(item.id) } } })
                    }
                    item { Text("✓ Comprados (${bought.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(bought, key = { it.id }) { item ->
                        ProductRow(item, onToggle = { scope.launch { dao.markProduct(item.id, false) } }, onDelete = { confirm { scope.launch { dao.deleteProduct(item.id) } } })
                    }
                }
            }
        }
    }

    if (dialogTitle != null) {
        AlertDialog(
            onDismissRequest = { dialogTitle = null },
            title = { Text(dialogTitle!!) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = textValue, onValueChange = { textValue = it }, label = { Text("Nombre") }, singleLine = true)
                    if (categoryId != null) OutlinedTextField(value = detailValue, onValueChange = { detailValue = it }, label = { Text("Cantidad / unidad (opcional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = textValue.isNotBlank(), onClick = {
                    val value = textValue.trim()
                    val detail = detailValue.trim()
                    scope.launch {
                        when {
                            categoryId != null -> dao.addProduct(Product(id = newId(), categoryId = categoryId!!, name = value, quantity = detail))
                            listId != null -> dao.addCategory(Category(id = newId(), listId = listId!!, name = value))
                            else -> dao.addList(ShoppingList(id = newId(), name = value))
                        }
                    }
                    dialogTitle = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { dialogTitle = null }) { Text("Cancelar") } }
        )
    }
    if (pendingAction != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Confirmar operación") },
            text = { Text("Esta operación puede modificar o borrar información. ¿Quieres continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ProductRow(item: Product, onToggle: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.purchased, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).clickable { onToggle() }) {
                Text(
                    item.name,
                    textDecoration = if (item.purchased) TextDecoration.LineThrough else null,
                    color = if (item.purchased) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (item.quantity.isNotBlank()) Text(item.quantity, style = MaterialTheme.typography.bodySmall)
            }
            if (item.purchased) Text("✓", color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onDelete) { Text("×") }
        }
    }
}
