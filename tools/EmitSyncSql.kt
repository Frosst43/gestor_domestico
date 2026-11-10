import com.frosst.gestordomestico.SyncSql
import java.util.Base64

fun main() {
    val groups = listOf(
        "TABLE" to SyncSql.tables,
        "IDENTITY" to listOf(SyncSql.identityInsert("12345678-1234-1234-1234-123456789abc")),
        "SEED" to SyncSql.seedStatements(),
        "TRIGGER" to SyncSql.triggerStatements()
    )
    groups.forEach { (kind, statements) ->
        statements.forEach { println(kind + "\t" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) }
    }
}
