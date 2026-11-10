package com.frosst.gestordomestico

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

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
        val json = JSONObject(BackupCodec.encode(example)).put("version", 2).toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json) }
    }

    @Test fun emptyBackupIsAllowed() {
        val empty = ShoppingBackup(emptyList(), emptyList(), emptyList())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }

    @Test fun numericMetadataRequiresExactIntegers() {
        for (invalid in listOf<Any>(1.5, 4294967297L, "1", true)) {
            val json = JSONObject(BackupCodec.encode(example)).put("version", invalid).toString()
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json) }
        }
        for (invalid in listOf<Any>(-1L, 1.5, "1", 9.223372036854776E18)) {
            val root = JSONObject(BackupCodec.encode(example))
            root.getJSONArray("lists").getJSONObject(0).put("createdAt", invalid)
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
        }
    }

    @Test fun negativeTimestampsAreRejectedBeforeExport() {
        val invalid = listOf(
            example.copy(lists = listOf(example.lists[0].copy(createdAt = -1))),
            example.copy(categories = listOf(example.categories[0].copy(updatedAt = -1))),
            example.copy(products = listOf(example.products[0].copy(updatedAt = -1)))
        )
        invalid.forEach { backup ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(backup) }
        }
    }

    @Test fun damagedOrAmbiguousDocumentsAreRejected() {
        val json = BackupCodec.encode(example)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json + "{}") }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("[".repeat(40) + "0" + "]".repeat(40))
        }
        assertThrows(CharacterCodingException::class.java) {
            BackupCodec.readLimited(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test fun textAcceptedByTheEditorRemainsBackupCompatible() {
        val longText = example.copy(products = listOf(example.products[0].copy(
            name = "N".repeat(501), quantity = "Q".repeat(501), notes = "x".repeat(8193)
        )))
        assertEquals(longText, BackupCodec.decode(BackupCodec.encode(longText)))
    }
}
