package com.frosst.gestordomestico

data class ShoppingProgress(val purchased: Int, val total: Int) {
    val completed: Boolean get() = total > 0 && purchased == total
    val fraction: Float get() = if (total == 0) 0f else purchased.toFloat() / total
}

fun progressOf(products: List<Product>): ShoppingProgress =
    ShoppingProgress(products.count { it.purchased }, products.size)

fun purchaseOrder(products: List<Product>): List<Product> =
    products.sortedWith(compareBy<Product> { it.purchased }.thenBy { it.name.lowercase() })
