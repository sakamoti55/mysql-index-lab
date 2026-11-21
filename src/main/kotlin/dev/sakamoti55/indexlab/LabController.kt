package dev.sakamoti55.indexlab

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class LabController(private val lab: LabService) {
    @MutationMapping
    fun seedUsers(@Argument rowCount: Int) = lab.seed(rowCount)

    @QueryMapping
    fun search(@Argument table: UserTable, @Argument email: String) = lab.search(table, email)

    @QueryMapping
    fun benchmark(@Argument email: String, @Argument repetitions: Int, @Argument warmup: Int) =
        lab.benchmark(email, repetitions, warmup)
}
