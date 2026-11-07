package com.frosst.gestordomestico

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shopping_lists")
data class ShoppingList(
    @PrimaryKey val id: String,
    val name: String,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories", foreignKeys = [ForeignKey(entity = ShoppingList::class, parentColumns = ["id"], childColumns = ["listId"], onDelete = ForeignKey.CASCADE)], indices = [Index("listId")])
data class Category(
    @PrimaryKey val id: String,
    val listId: String,
    val name: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "products", foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("categoryId")])
data class Product(
    @PrimaryKey val id: String,
    val categoryId: String,
    val name: String,
    val quantity: String = "",
    val notes: String = "",
    val purchased: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_lists ORDER BY createdAt, id")
    fun observeLists(): Flow<List<ShoppingList>>

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE")
    fun observeCategories(): Flow<List<Category>>

    @Query("SELECT * FROM products ORDER BY purchased ASC, name COLLATE NOCASE")
    fun observeProducts(): Flow<List<Product>>

    @Insert suspend fun addList(value: ShoppingList)
    @Insert suspend fun addCategory(value: Category)
    @Insert suspend fun addProduct(value: Product)

    @Query("UPDATE shopping_lists SET archived = :archived, updatedAt = :time WHERE id = :id")
    suspend fun archiveList(id: String, archived: Boolean, time: Long = System.currentTimeMillis())

    @Query("UPDATE products SET purchased = :purchased, updatedAt = :time WHERE id = :id")
    suspend fun markProduct(id: String, purchased: Boolean, time: Long = System.currentTimeMillis())

    @Query("UPDATE products SET purchased = 0, updatedAt = :time WHERE categoryId IN (SELECT id FROM categories WHERE listId = :listId)")
    suspend fun resetList(listId: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM products WHERE purchased = 1 AND categoryId IN (SELECT id FROM categories WHERE listId = :listId)")
    suspend fun clearPurchased(listId: String)

    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: String)

    @Query("SELECT * FROM categories WHERE listId = :listId")
    suspend fun categoriesForList(listId: String): List<Category>

    @Query("SELECT * FROM products WHERE categoryId = :categoryId")
    suspend fun productsForCategory(categoryId: String): List<Product>

    @Transaction
    suspend fun duplicateList(source: ShoppingList, newId: () -> String) {
        val listId = newId()
        addList(ShoppingList(id = listId, name = source.name + " (copia)"))
        categoriesForList(source.id).forEach { oldCategory ->
            val categoryId = newId()
            addCategory(Category(id = categoryId, listId = listId, name = oldCategory.name))
            productsForCategory(oldCategory.id).forEach { oldProduct ->
                addProduct(Product(id = newId(), categoryId = categoryId, name = oldProduct.name, quantity = oldProduct.quantity, notes = oldProduct.notes))
            }
        }
    }
}

@Database(entities = [ShoppingList::class, Category::class, Product::class], version = 1, exportSchema = false)
abstract class ShoppingDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao
}
