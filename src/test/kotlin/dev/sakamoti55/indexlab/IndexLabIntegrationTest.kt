package dev.sakamoti55.indexlab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndexLabIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun graphql(query: String): JsonNode {
        val response = http.postForEntity("/graphql", mapOf("query" to query), String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(200)
        return mapper.readTree(response.body)
    }

    private fun success(query: String): JsonNode {
        val result = graphql(query)
        assertThat(result.has("errors")).withFailMessage(result.toPrettyString()).isFalse()
        return result.path("data")
    }

    @Test
    fun `same data is searched through GraphQL using different access paths`() {
        success("mutation { seedUsers(rowCount: 10000) { rowsPerTable } }")
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM Usertable1 a JOIN Usertable2 b ON a.id=b.id AND a.name=b.name AND a.email=b.email AND a.age=b.age",
            Long::class.java,
        )!!).isEqualTo(10000L)
        val indexes1 = jdbc.queryForList("SHOW INDEX FROM Usertable1").map { it["Key_name"] }
        val indexes2 = jdbc.queryForList("SHOW INDEX FROM Usertable2").map { it["Key_name"] }
        assertThat(indexes1).containsExactly("PRIMARY")
        assertThat(indexes2).containsExactlyInAnyOrder("PRIMARY", "idx_usertable2_email")

        val data = success("""
            { a: search(table: TABLE1, email: "user5000@example.com") { users { id name email age } }
              b: search(table: TABLE2, email: "user5000@example.com") { users { id name email age } }
              benchmark(email: "user5000@example.com", repetitions: 4, warmup: 1) {
                table1 { matchedRows samplesMs medianMs explainAnalyze }
                table2 { matchedRows samplesMs medianMs explainAnalyze } speedup
              }
            }
        """.trimIndent())
        assertThat(data.path("a").path("users")).isEqualTo(data.path("b").path("users"))
        assertThat(data.at("/a/users/0/id").asText()).isEqualTo("5000")
        val benchmark = data.path("benchmark")
        for (table in listOf("table1", "table2")) {
            assertThat(benchmark.path(table).path("matchedRows").asInt()).isEqualTo(1)
            assertThat(benchmark.path(table).path("samplesMs").size()).isEqualTo(4)
            assertThat(benchmark.path(table).path("medianMs").asDouble()).isGreaterThan(0.0)
        }
        assertThat(benchmark.at("/table1/explainAnalyze").asText()).contains("Table scan on Usertable1")
        assertThat(benchmark.at("/table2/explainAnalyze").asText()).contains("idx_usertable2_email")
        // Do not assert a speed ratio: timing depends on host load and cache state.
        val missing = success("""{ benchmark(email: "missing@example.com", repetitions: 2, warmup: 0) {
            table1 { matchedRows } table2 { matchedRows }
        } }""")
        assertThat(missing.at("/benchmark/table1/matchedRows").asInt()).isZero()
        assertThat(missing.at("/benchmark/table2/matchedRows").asInt()).isZero()

        // Invalid input must not clear the current dataset.
        assertThat(graphql("mutation { seedUsers(rowCount: 0) { rowsPerTable } }").has("errors")).isTrue()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Usertable1", Long::class.java)!!).isEqualTo(10000L)
        assertThat(graphql("""{ benchmark(email: "x", repetitions: 0) { speedup } }""").has("errors")).isTrue()
        success("mutation { seedUsers(rowCount: 10) { rowsPerTable } }")
        for (table in UserTable.entries) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ${table.sqlName}", Long::class.java)!!).isEqualTo(10L)
        }
    }
}
