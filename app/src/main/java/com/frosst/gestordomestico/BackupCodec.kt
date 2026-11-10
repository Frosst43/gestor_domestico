package com.frosst.gestordomestico

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ShoppingBackup(
    val lists: List<ShoppingList>,
    val categories: List<Category>,
    val products: List<Product>
)

/** Portable, versioned backup format. Exported JSON is not encrypted. */
object BackupCodec {
    private const val FORMAT = "gestor_domestico_backup"
    private const val VERSION = 1L
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_LISTS = 5000
    private const val MAX_CATEGORIES = 25000
    private const val MAX_PRODUCTS = 100000

    fun encode(backup: ShoppingBackup): String {
        validate(backup)
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exported_at", System.currentTimeMillis())
        root.put("lists", JSONArray().apply {
            backup.lists.forEach { list ->
                put(JSONObject().apply {
                    put("id", list.id)
                    put("name", list.name)
                    put("archived", list.archived)
                    put("createdAt", list.createdAt)
                    put("updatedAt", list.updatedAt)
                })
            }
        })
        root.put("categories", JSONArray().apply {
            backup.categories.forEach { category ->
                put(JSONObject().apply {
                    put("id", category.id)
                    put("listId", category.listId)
                    put("name", category.name)
                    put("updatedAt", category.updatedAt)
                })
            }
        })
        root.put("products", JSONArray().apply {
            backup.products.forEach { product ->
                put(JSONObject().apply {
                    put("id", product.id)
                    put("categoryId", product.categoryId)
                    put("name", product.name)
                    put("quantity", product.quantity)
                    put("notes", product.notes)
                    put("purchased", product.purchased)
                    put("updatedAt", product.updatedAt)
                })
            }
        })
        return root.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "La copia supera 20 MiB" }
        }
    }

    fun decode(json: String): ShoppingBackup {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Archivo demasiado grande" }
        checkNesting(json)
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Se esperaba una copia JSON")
        require(tokener.nextClean() == '\u0000') { "Contenido adicional al final de la copia" }
        require(root.opt("format") == FORMAT) { "Formato de copia no reconocido" }
        require(root.integer("version") == VERSION) { "Version de copia no compatible" }
        val lists = root.getJSONArray("lists").also { require(it.length() <= MAX_LISTS) { "Demasiadas listas" } }
        val categories = root.getJSONArray("categories").also { require(it.length() <= MAX_CATEGORIES) { "Demasiadas categorias" } }
        val products = root.getJSONArray("products").also { require(it.length() <= MAX_PRODUCTS) { "Demasiados productos" } }

        return ShoppingBackup(
            lists = (0 until lists.length()).map { i ->
                lists.getJSONObject(i).let { item ->
                    ShoppingList(
                        id = item.string("id", 128),
                        name = item.string("name"),
                        archived = item.boolean("archived"),
                        createdAt = item.integer("createdAt"),
                        updatedAt = item.integer("updatedAt")
                    )
                }
            },
            categories = (0 until categories.length()).map { i ->
                categories.getJSONObject(i).let { item ->
                    Category(
                        id = item.string("id", 128),
                        listId = item.string("listId", 128),
                        name = item.string("name"),
                        updatedAt = item.integer("updatedAt")
                    )
                }
            },
            products = (0 until products.length()).map { i ->
                products.getJSONObject(i).let { item ->
                    Product(
                        id = item.string("id", 128),
                        categoryId = item.string("categoryId", 128),
                        name = item.string("name"),
                        quantity = item.string("quantity", allowEmpty = true),
                        notes = item.string("notes", allowEmpty = true),
                        purchased = item.boolean("purchased"),
                        updatedAt = item.integer("updatedAt")
                    )
                }
            }
        ).also { validate(it) }
    }

    fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            total += count
            require(total <= MAX_BYTES) { "Archivo demasiado grande (maximo 20 MiB)" }
            out.write(buffer, 0, count)
        }
        // Do not silently replace damaged bytes in IDs, names or notes.
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }

    private fun JSONObject.string(key: String, maximum: Int = MAX_BYTES, allowEmpty: Boolean = false): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Campo $key invalido")
        require(value.length <= maximum && (allowEmpty || value.isNotBlank())) { "Campo $key invalido" }
        return value
    }

    private fun JSONObject.boolean(key: String): Boolean =
        opt(key) as? Boolean ?: throw IllegalArgumentException("Campo $key invalido")

    // Reject fractions, numeric strings, non-finite numbers and narrowing overflow.
    private fun JSONObject.integer(key: String): Long {
        val value = when (val raw = opt(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw IllegalArgumentException("Campo $key debe ser un entero")
        }
        require(value >= 0) { "Campo $key no puede ser negativo" }
        return value
    }

    // Bound parser recursion before invoking org.json, including unknown fields.
    private fun checkNesting(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        require(depth <= 32) { "Archivo JSON demasiado anidado" }
                    }
                    '}', ']' -> {
                        depth--
                        require(depth >= 0) { "Estructura JSON invalida" }
                    }
                }
            }
        }
        require(depth == 0 && !inString) { "Archivo JSON incompleto" }
    }

    fun validate(backup: ShoppingBackup) {
        require(backup.lists.size <= MAX_LISTS && backup.categories.size <= MAX_CATEGORIES &&
                backup.products.size <= MAX_PRODUCTS) { "La copia supera los limites permitidos" }
        val listIds = backup.lists.map { it.id }.toHashSet()
        val categoryIds = backup.categories.map { it.id }.toHashSet()
        val productIds = backup.products.map { it.id }.toHashSet()
        require(backup.lists.size == listIds.size) { "Listas duplicadas" }
        require(backup.categories.size == categoryIds.size) { "Categorias duplicadas" }
        require(backup.products.size == productIds.size) { "Productos duplicados" }
        require(backup.lists.all {
            it.id.isNotBlank() && it.id.length <= 128 && it.name.isNotBlank() &&
            it.createdAt >= 0 && it.updatedAt >= 0
        }) { "Lista invalida" }
        require(backup.categories.all {
            it.id.isNotBlank() && it.id.length <= 128 && it.listId in listIds &&
            it.name.isNotBlank() && it.updatedAt >= 0
        }) { "Categoria sin lista o invalida" }
        require(backup.products.all {
            it.id.isNotBlank() && it.id.length <= 128 && it.categoryId in categoryIds &&
            it.name.isNotBlank() && it.updatedAt >= 0
        }) { "Producto sin categoria o invalido" }
    }
}
