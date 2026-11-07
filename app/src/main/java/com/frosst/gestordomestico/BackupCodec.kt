package com.frosst.gestordomestico

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class ShoppingBackup(
    val lists: List<ShoppingList>,
    val categories: List<Category>,
    val products: List<Product>
)

/** Portable, versioned backup format. The JSON is deliberately not encrypted. */
object BackupCodec {
    private const val FORMAT = "gestor_domestico_backup"
    private const val VERSION = 1
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
        val root = JSONObject(json)
        require(root.opt("format") == FORMAT) { "Formato de copia no reconocido" }
        require(root.opt("version") is Number && (root.get("version") as Number).toInt() == VERSION) {
            "Version de copia no compatible"
        }
        val lists = root.getJSONArray("lists").also { require(it.length() <= MAX_LISTS) { "Demasiadas listas" } }
        val categories = root.getJSONArray("categories").also { require(it.length() <= MAX_CATEGORIES) { "Demasiadas categorias" } }
        val products = root.getJSONArray("products").also { require(it.length() <= MAX_PRODUCTS) { "Demasiados productos" } }

        return ShoppingBackup(
            lists = (0 until lists.length()).map { i ->
                lists.getJSONObject(i).let { item ->
                    ShoppingList(
                        id = item.string("id", 128),
                        name = item.string("name", 500),
                        archived = item.boolean("archived"),
                        createdAt = item.long("createdAt"),
                        updatedAt = item.long("updatedAt")
                    )
                }
            },
            categories = (0 until categories.length()).map { i ->
                categories.getJSONObject(i).let { item ->
                    Category(
                        id = item.string("id", 128),
                        listId = item.string("listId", 128),
                        name = item.string("name", 500),
                        updatedAt = item.long("updatedAt")
                    )
                }
            },
            products = (0 until products.length()).map { i ->
                products.getJSONObject(i).let { item ->
                    Product(
                        id = item.string("id", 128),
                        categoryId = item.string("categoryId", 128),
                        name = item.string("name", 500),
                        quantity = item.string("quantity", 500, allowEmpty = true),
                        notes = item.string("notes", 8192, allowEmpty = true),
                        purchased = item.boolean("purchased"),
                        updatedAt = item.long("updatedAt")
                    )
                }
            }
        ).also { validate(it) }
    }

    // A bounded reader prevents an untrusted document from consuming unlimited memory.
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
        return out.toString(Charsets.UTF_8.name())
    }

    private fun JSONObject.string(key: String, maximum: Int, allowEmpty: Boolean = false): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Campo $key invalido")
        require(value.length <= maximum && (allowEmpty || value.isNotBlank())) { "Campo $key invalido" }
        return value
    }

    private fun JSONObject.boolean(key: String): Boolean =
        opt(key) as? Boolean ?: throw IllegalArgumentException("Campo $key invalido")

    private fun JSONObject.long(key: String): Long {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("Campo $key invalido")
        val long = value.toLong()
        require(long >= 0 && long.toDouble() == value.toDouble()) { "Campo $key invalido" }
        return long
    }

    fun validate(backup: ShoppingBackup) {
        require(backup.lists.size <= MAX_LISTS && backup.categories.size <= MAX_CATEGORIES &&
                backup.products.size <= MAX_PRODUCTS) { "La copia supera los limites permitidos" }
        val listIds = backup.lists.map { it.id }
        val categoryIds = backup.categories.map { it.id }
        val productIds = backup.products.map { it.id }
        require(listIds.size == listIds.toSet().size) { "Listas duplicadas" }
        require(categoryIds.size == categoryIds.toSet().size) { "Categorias duplicadas" }
        require(productIds.size == productIds.toSet().size) { "Productos duplicados" }
        require(backup.lists.all { it.id.isNotBlank() && it.id.length <= 128 && it.name.isNotBlank() && it.name.length <= 500 }) {
            "Lista invalida"
        }
        require(backup.categories.all {
            it.id.isNotBlank() && it.id.length <= 128 &&
            it.listId in listIds && it.name.isNotBlank() && it.name.length <= 500
        }) { "Categoria sin lista o invalida" }
        require(backup.products.all {
            it.id.isNotBlank() && it.id.length <= 128 &&
            it.categoryId in categoryIds && it.name.isNotBlank() && it.name.length <= 500 &&
            it.quantity.length <= 500 && it.notes.length <= 8192
        }) { "Producto sin categoria o invalido" }
    }
}
