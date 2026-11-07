package com.frosst.gestordomestico

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val example = ShoppingBackup(
        lists = listOf(ShoppingList(id = "list-1", name = "Necesidades casa", archived = true, createdAt = 1, updatedAt = 2)),
        categories = listOf(Category(id = "cat-1", listId = "list-1", name = "Electrodomesticos", updatedAt = 3)),
        products = listOf(Product(id = "p-1", categoryId = "cat-1", name = "Lavadora", quantity = "1", notes = "Mirar medidas", purchased = true, updatedAt = 4))
    )

    @Test fun backupRoundTripPreservesContents() {
        assertEquals(example, BackupCodec.decode(BackupCodec.encode(example)))
    }

    @Test fun orphanReferencesAreRejected() {
        val broken = example.copy(categories = listOf(example.categories[0].copy(listId = "missing")))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(broken) }
    }

    @Test fun duplicateIdentifiersAreRejected() {
        val broken = example.copy(products = example.products + example.products[0])
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(broken) }
    }

    @Test fun unsupportedBackupVersionIsRejected() {
        val json = BackupCodec.encode(example).replace("\"version\":1", "\"version\":2")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json) }
    }

    @Test fun emptyBackupIsAllowed() {
        val empty = ShoppingBackup(emptyList(), emptyList(), emptyList())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }
}
