package com.example.waitasecond.core.distraction

import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus

object DbmsRelevanceScorer {
    // Kept in one place so the controlled DBMS demo vocabulary is easy to tune.
    private val strongDbmsKeywords = listOf(
        "dbms",
        "database management system",
        "normalization",
        "normalisation",
        "1nf",
        "2nf",
        "3nf",
        "bcnf",
        "relational database",
        "relational model",
        "functional dependency",
        "er diagram",
        "entity relationship"
    )

    private val dbmsKeywords = listOf(
        "database",
        "sql",
        "transaction",
        "acid",
        "concurrency",
        "indexing",
        "index",
        "primary key",
        "foreign key",
        "candidate key",
        "schema",
        "query",
        "join"
    )

    private val distractionKeywords = listOf(
        "gta",
        "gaming",
        "gameplay",
        "minecraft",
        "valorant",
        "movie",
        "trailer",
        "music",
        "song",
        "vlog",
        "comedy",
        "meme",
        "football",
        "cricket",
        "entertainment",
        "mrbeast",
        "mr beast"
    )

    fun score(content: String, currentChannel: String? = null): RelevanceResult {
        val normalized = content.lowercase()
        val strongMatches = strongDbmsKeywords.filter { normalized.containsKeyword(it) }
        val dbmsMatches = dbmsKeywords.filter { normalized.containsKeyword(it) }
        val distractionMatches = distractionKeywords.filter { normalized.containsKeyword(it) }

        val entertainmentChannel = currentChannel?.trim()?.lowercase()
            ?.removePrefix("@") in setOf("mrbeast", "mr beast", "mrbeast gaming", "mrbeastgaming")
        val result = when {
            strongMatches.isNotEmpty() -> RelevanceResult(
                score = 0.9,
                status = RelevanceStatus.RELEVANT,
                reason = "Matched strong DBMS keywords: ${strongMatches.joinToString()}"
            )

            dbmsMatches.isNotEmpty() -> RelevanceResult(
                score = if (dbmsMatches.size >= 2) 0.8 else 0.7,
                status = RelevanceStatus.RELEVANT,
                reason = "Matched DBMS keywords: ${dbmsMatches.joinToString()}"
            )

            entertainmentChannel -> RelevanceResult(0.1, RelevanceStatus.IRRELEVANT,
                "Current video channel is MrBeast entertainment")

            distractionMatches.isNotEmpty() -> RelevanceResult(
                score = 0.1,
                status = RelevanceStatus.IRRELEVANT,
                reason = "Matched distraction keywords: ${distractionMatches.joinToString()}"
            )

            else -> RelevanceResult(
                score = 0.5,
                status = RelevanceStatus.UNCERTAIN,
                reason = "No clear DBMS or distraction keywords found"
            )
        }

        return result
    }

    private fun String.containsKeyword(keyword: String): Boolean {
        return Regex("(?<![a-z0-9])${Regex.escape(keyword)}(?![a-z0-9])").containsMatchIn(this)
    }
}
