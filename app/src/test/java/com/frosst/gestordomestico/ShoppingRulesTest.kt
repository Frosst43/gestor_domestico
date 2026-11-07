package com.frosst.gestordomestico

import org.junit.Assert.*
import org.junit.Test

class ShoppingRulesTest {
    private fun product(name: String, purchased: Boolean) =
        Product(id = name, categoryId = "category", name = name, purchased = purchased)

    @Test fun purchasedItemsComeLast() {
        val values = purchaseOrder(listOf(product("Pan", true), product("Zumo", false), product("Arroz", false)))
        assertEquals(listOf("Arroz", "Zumo", "Pan"), values.map { it.name })
        assertEquals(listOf(false, false, true), values.map { it.purchased })
    }

    @Test fun emptyListIsNotCompleted() {
        val result = progressOf(emptyList())
        assertFalse(result.completed)
        assertEquals(0f, result.fraction)
    }

    @Test fun completedListHasFullProgress() {
        val result = progressOf(listOf(product("Pan", true), product("Leche", true)))
        assertTrue(result.completed)
        assertEquals(1f, result.fraction)
    }
}
