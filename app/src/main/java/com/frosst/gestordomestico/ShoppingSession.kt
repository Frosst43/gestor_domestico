package com.frosst.gestordomestico

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class EditKind { LIST, CATEGORY, PRODUCT }

internal class EditRequest(
    val kind: EditKind,
    val id: String? = null,
    name: String = "",
    quantity: String = "",
    notes: String = ""
) {
    val newId: String = UUID.randomUUID().toString()
    var name by mutableStateOf(name)
    var quantity by mutableStateOf(quantity)
    var notes by mutableStateOf(notes)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

internal class QuickDraft(val listId: String, categoryId: String) {
    var newId: String = UUID.randomUUID().toString()
    var categoryId by mutableStateOf(categoryId)
    var name by mutableStateOf("")
    var quantity by mutableStateOf("")
    var notes by mutableStateOf("")
    var keepAdding by mutableStateOf(true)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var savedCount by mutableStateOf(0)
}

internal data class UiNotice(val text: String, val id: String = UUID.randomUUID().toString())

/** Retained across activity recreation. Never stores an Activity or its views.
 * Drafts survive rotation/theme recreation, NOT process death or force-stop.
 * Large notes are deliberately not serialized into Android's saved-state Bundle.
 */
class ShoppingSession : ViewModel() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val listIdState = mutableStateOf<String?>(null)
    internal val categoryIdState = mutableStateOf<String?>(null)
    internal val archivedState = mutableStateOf(false)
    internal val searchState = mutableStateOf("")
    internal val editState = mutableStateOf<EditRequest?>(null)
    internal val quickState = mutableStateOf<QuickDraft?>(null)
    internal var notice by mutableStateOf<UiNotice?>(null)
        private set
    internal var restoring by mutableStateOf(false)
        private set
    internal var pendingRestore by mutableStateOf<ShoppingBackup?>(null)

    internal fun consumeNotice(value: UiNotice) {
        if (notice == value) notice = null
    }

    internal fun operation(action: suspend () -> Unit) {
        if (restoring) return
        work.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                notice = UiNotice("No se pudo completar la operacion: ${error.message ?: "error desconocido"}")
            }
        }
    }

    internal fun saveEdit(dao: ShoppingDao) {
        val draft = editState.value ?: return
        if (draft.saving || restoring || draft.name.isBlank()) return
        val name = draft.name.trim()
        val quantity = draft.quantity.trim()
        val notes = draft.notes.trim()
        val parentList = listIdState.value
        val parentCategory = categoryIdState.value
        draft.saving = true
        draft.error = null
        work.launch {
            try {
                when (draft.kind) {
                    EditKind.LIST -> if (draft.id == null) {
                        dao.addList(ShoppingList(draft.newId, name))
                        archivedState.value = false
                    } else dao.renameList(draft.id, name)
                    EditKind.CATEGORY -> if (draft.id == null) {
                        dao.addCategory(Category(draft.newId, requireNotNull(parentList) { "Falta la lista de destino" }, name))
                    } else dao.renameCategory(draft.id, name)
                    EditKind.PRODUCT -> if (draft.id == null) {
                        dao.addProduct(Product(draft.newId, requireNotNull(parentCategory) { "Falta la categoria de destino" }, name, quantity, notes))
                    } else dao.editProduct(draft.id, name, quantity, notes)
                }
                // Do not dismiss another editor or a newer draft.
                if (editState.value === draft) editState.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                draft.error = "No se pudo guardar: ${error.message ?: "error desconocido"}"
            } finally { draft.saving = false }
        }
    }

    internal fun saveQuick(dao: ShoppingDao, categoryExists: Boolean) {
        val draft = quickState.value ?: return
        if (draft.saving || restoring || draft.name.isBlank()) return
        if (!categoryExists) {
            draft.error = "Selecciona una categoria existente"
            return
        }
        val product = Product(draft.newId, draft.categoryId, draft.name.trim(), draft.quantity.trim(), draft.notes.trim())
        val keepAdding = draft.keepAdding
        draft.saving = true
        draft.error = null
        work.launch {
            try {
                dao.addProduct(product)
                draft.savedCount++
                if (keepAdding) {
                    draft.name = ""
                    draft.quantity = ""
                    draft.notes = ""
                    draft.newId = UUID.randomUUID().toString()
                } else if (quickState.value === draft) quickState.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                draft.error = "No se pudo guardar: ${error.message ?: "error desconocido"}"
            } finally { draft.saving = false }
        }
    }

    internal fun restore(dao: ShoppingDao, backup: ShoppingBackup) {
        if (restoring || editState.value?.saving == true || quickState.value?.saving == true) return
        restoring = true
        work.launch {
            try {
                dao.restoreBackup(backup)
                pendingRestore = null
                listIdState.value = null
                categoryIdState.value = null
                archivedState.value = false
                searchState.value = ""
                editState.value = null
                quickState.value = null
                notice = UiNotice("Copia restaurada correctamente")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                notice = UiNotice("No se pudo restaurar: ${error.message ?: "error desconocido"}")
            } finally { restoring = false }
        }
    }

    override fun onCleared() {
        work.cancel()
        super.onCleared()
    }
}
