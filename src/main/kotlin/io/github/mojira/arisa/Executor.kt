package io.github.mojira.arisa

import arrow.syntax.function.partially1
import com.uchuhimo.konf.Config
import io.github.mojira.arisa.domain.cloud.CloudIssue
import io.github.mojira.arisa.infrastructure.CommentCache
import io.github.mojira.arisa.infrastructure.ProjectCache
import io.github.mojira.arisa.infrastructure.config.Arisa
import io.github.mojira.arisa.infrastructure.jira.toDomain
import io.github.mojira.arisa.registry.ModuleRegistry
import io.github.mojira.arisa.registry.getModuleRegistries

class Executor(
    private val config: Config,
    private val registries: List<ModuleRegistry<CloudIssue>> = getModuleRegistries(config),
    private val searchIssues: (String, Int, () -> Unit) -> List<CloudIssue> =
        ::getSearchResultsFromJira.partially1(config).partially1(MAX_RESULTS)
) {
    companion object {
        private const val MAX_RESULTS = 100
    }

    data class ExecutionResults(
        val successful: Boolean,
        val failedTickets: Set<String>
    )

    @Suppress("TooGenericExceptionCaught")
    fun execute(
        timeframe: ExecutionTimeframe,
        rerunTickets: Set<String>
    ): ExecutionResults {
        val failedTickets = mutableSetOf<String>()

        log.debug("Executing timeframe $timeframe")

        try {
            registries.forEach {
                executeRegistry(it, rerunTickets, timeframe) { ticket ->
                    if (ticket !in rerunTickets) {
                        failedTickets.add(ticket)
                    } else {
                        log.info("$ticket failed to run again, dropping it.")
                    }
                }
            }
        } catch (ex: Throwable) {
            log.error("Failed to execute modules", ex)
            return ExecutionResults(false, failedTickets)
        } finally {
            CommentCache.flush()
        }

        return ExecutionResults(true, failedTickets)
    }

    private fun executeRegistry(
        registry: ModuleRegistry<CloudIssue>,
        rerunTickets: Collection<String>,
        timeframe: ExecutionTimeframe,
        addFailedTicket: (String) -> Unit
    ) {
        val issues = getIssuesForRegistry(registry, rerunTickets, timeframe)

        @Suppress("DestructuringDeclarationWithTooManyEntries")
        registry.getEnabledModules().forEach { (moduleName, _, execute, moduleExecutor) ->
            log.debug("Executing module $moduleName")
            moduleExecutor.executeModule(issues, addFailedTicket) { issue -> execute(issue, timeframe) }
        }
    }

    private fun getIssuesForRegistry(
        registry: ModuleRegistry<CloudIssue>,
        rerunTickets: Collection<String>,
        timeframe: ExecutionTimeframe
    ): List<CloudIssue> {
        val issues = mutableListOf<CloudIssue>()

        val jql = registry.getFullJql(timeframe, rerunTickets)

        if (config[Arisa.Debug.logQueryJql]) {
            log.debug("${registry::class.simpleName} JQL: `$jql`")
        }

        var continueSearching = true
        var startAt = 0

        while (continueSearching) {
            val searchResult = searchIssues(
                jql,
                startAt
            ) { continueSearching = false }

            issues.addAll(searchResult)

            startAt += MAX_RESULTS
        }

        if (config[Arisa.Debug.logReturnedIssues]) {
            log.debug("Returned issues for registry ${registry::class.simpleName}: ${issues.map { it.key }}")
        } else {
            log.debug("${issues.size} issues have been returned for registry ${registry::class.simpleName}")
        }

        return issues
    }

    init {
        val enabledModules = registries.flatMap { registry -> registry.getEnabledModules().map { it.name } }
        log.debug("Enabled modules: $enabledModules")
    }
}

private fun getSearchResultsFromJira(
    config: Config,
    maxResults: Int,
    jql: String,
    startAt: Int,
    finishedCallback: () -> Unit
): List<CloudIssue> {
    val searchResult = jiraClient.searchIssues(
        jql,
        listOf("*all"),
        listOf("changelog"),
        maxResults,
        startAt
    ) ?: return emptyList()

    if (startAt + searchResult.maxResults >= searchResult.total) finishedCallback()

    return searchResult
        .issues
        .mapNotNull {
            @Suppress("TooGenericExceptionCaught")
            try {
                it.toDomain(
                    jiraClient,
                    ProjectCache.getProjectFromTicketId(it.key),
                    config
                )
            } catch (exception: Exception) {
                log.error("Error mapping bug report ${it.key}", exception)
                null
            }
        }
}
