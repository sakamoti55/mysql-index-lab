package dev.sakamoti55.indexlab

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

// SQL identifiers come only from this enum; email values use bound parameters.
enum class UserTable(val sqlName: String) { TABLE1("Usertable1"), TABLE2("Usertable2") }
data class User(val id: Long, val name: String, val email: String, val age: Int)
data class SeedResult(val rowsPerTable: Int)
data class SearchResult(val table: UserTable, val users: List<User>, val elapsedMs: Double)
data class TableBenchmark(
    val table: UserTable,
    val matchedRows: Int,
    val samplesMs: List<Double>,
    val averageMs: Double,
    val medianMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val explainAnalyze: String,
)
data class BenchmarkResult(
    val email: String,
    val warmup: Int,
    val repetitions: Int,
    val table1: TableBenchmark,
    val table2: TableBenchmark,
    val speedup: Double,
)

@Service
class LabService(private val jdbc: JdbcTemplate, private val transaction: TransactionTemplate) {
    // Serializes this local lab's operations so seeding cannot overlap measurement.
    @Synchronized
    fun seed(rowCount: Int): SeedResult {
        require(rowCount in 1..1_000_000) { "rowCount must be between 1 and 1,000,000" }
        transaction.executeWithoutResult {
            jdbc.update("DELETE FROM Usertable2")
            jdbc.update("DELETE FROM Usertable1")
            (1..rowCount).chunked(1000).forEach { ids ->
                jdbc.batchUpdate(
                    "INSERT INTO Usertable1 (id, name, email, age) VALUES (?, ?, ?, ?)",
                    ids.map { id -> arrayOf<Any>(id, "User $id", "user$id@example.com", 18 + id % 63) },
                )
            }
            jdbc.update("INSERT INTO Usertable2 (id, name, email, age) SELECT id, name, email, age FROM Usertable1")
        }
        // Run after commit: ANALYZE TABLE itself can cause an implicit commit in MySQL.
        UserTable.entries.forEach { jdbc.queryForList("ANALYZE TABLE ${it.sqlName}") }
        return SeedResult(rowCount)
    }

    @Synchronized
    fun search(table: UserTable, email: String): SearchResult {
        require(email.length <= 255) { "email must be at most 255 characters" }
        val start = System.nanoTime()
        val users = jdbc.query(select(table), { rs, _ ->
            User(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getInt("age"))
        }, email)
        return SearchResult(table, users, (System.nanoTime() - start) / 1_000_000.0)
    }

    @Synchronized
    fun benchmark(email: String, repetitions: Int, warmup: Int): BenchmarkResult {
        require(repetitions in 1..200) { "repetitions must be between 1 and 200" }
        require(warmup in 0..20) { "warmup must be between 0 and 20" }
        require(email.length <= 255) { "email must be at most 255 characters" }
        require(jdbc.queryForObject("SELECT COUNT(*) FROM Usertable1", Long::class.java)!! > 0) {
            "Run seedUsers before benchmarking"
        }
        repeat(warmup) {
            search(UserTable.TABLE1, email)
            search(UserTable.TABLE2, email)
        }
        val samples = UserTable.entries.associateWith { mutableListOf<Double>() }
        val matches = mutableMapOf<UserTable, Int>()
        repeat(repetitions) { iteration ->
            // Alternate the first table to reduce ordering bias.
            val order = if (iteration % 2 == 0) UserTable.entries else UserTable.entries.reversed()
            order.forEach { table ->
                val result = search(table, email)
                samples.getValue(table).add(result.elapsedMs)
                matches[table] = result.users.size
            }
        }
        fun summarize(table: UserTable): TableBenchmark {
            val values = samples.getValue(table).toList()
            val sorted = values.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
            val plan = jdbc.queryForObject("EXPLAIN ANALYZE ${select(table)}", String::class.java, email)!!
            return TableBenchmark(table, matches.getValue(table), values, values.average(), median,
                sorted.first(), sorted.last(), plan)
        }
        val table1 = summarize(UserTable.TABLE1)
        val table2 = summarize(UserTable.TABLE2)
        return BenchmarkResult(email, warmup, repetitions, table1, table2, table1.medianMs / table2.medianMs)
    }

    private fun select(table: UserTable) = "SELECT id, name, email, age FROM ${table.sqlName} WHERE email = ?"
}
