package com.vayunmathur.youpipe.util

import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Instant

/** Where a recommendation candidate originated. */
enum class RecSource { RELATED, TRENDING, SUBSCRIPTION, TOP_CHANNEL, SEARCH }

/**
 * A recommendation candidate plus its provenance. [sourceWatchedAt] is the time
 * the source video was watched and is only meaningful for [RecSource.RELATED].
 */
data class Candidate(
    val video: VideoInfo,
    val source: RecSource,
    val sourceWatchedAt: Instant? = null,
)

/**
 * All ranking tunables in one place, replacing the scattered magic numbers of the
 * previous inline scorer.
 */
data class RecommendationWeights(
    val authorAffinityWeight: Double = 2.0,
    val authorAffinityCap: Double = 10.0,
    val sourceRecencyWeight: Double = 5.0,
    val keywordWeight: Double = 4.0,
    val uploadFreshnessDecay: Double = 0.03,
    val explorationBase: Double = 0.5,
    val mmrLambda: Double = 0.7,
    val perAuthorCap: Int = 3,
    val subscriptionDamping: Double = 0.5,
    val maxSubscriptionFraction: Double = 0.3,
    val maxResults: Int = 50,
    /** Half-life (days) for down-weighting older watches when building the profile. */
    val recencyHalfLifeDays: Double = 14.0,
    /** Completion assumed for history items without a known duration (e.g. imports). */
    val neutralCompletion: Double = 0.5,
)

/** Learned interest signals derived from watch history. */
data class InterestProfile(
    val authorWeights: Map<String, Double>,
    val keywordWeights: Map<String, Double>,
)

private val STOPWORDS = setOf(
    "the", "and", "for", "with", "you", "your", "this", "that", "how",
    "why", "what", "are", "was", "were", "from", "have", "has", "not",
    "but", "all", "can", "will", "into", "out", "new", "get", "got",
)

/** Lowercases, strips punctuation, splits on whitespace, and drops stopwords / short tokens. */
fun tokenize(title: String): List<String> =
    title.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in STOPWORDS }

/**
 * Builds an [InterestProfile] from watch history.
 *
 * Author affinity is engagement-weighted (watch completion) and recency-weighted,
 * so channels the user actually finishes recently outrank channels merely opened
 * long ago. Keyword weights are a recency-weighted term frequency over watched titles.
 */
fun buildInterestProfile(
    history: List<HistoryVideo>,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
): InterestProfile {
    val authorWeights = HashMap<String, Double>()
    val keywordWeights = HashMap<String, Double>()
    val halfLife = ln(2.0) / weights.recencyHalfLifeDays

    for (h in history) {
        val v = h.videoItem
        val completion = if (v.duration > 0) {
            (h.progress.toDouble() / (v.duration * 1000)).coerceIn(0.0, 1.0)
        } else {
            weights.neutralCompletion
        }
        val ageDays = (now - h.timestamp).inWholeDays.toDouble().coerceAtLeast(0.0)
        val recency = exp(-halfLife * ageDays)
        val contribution = completion * recency

        val author = v.author.lowercase()
        if (author.isNotBlank()) {
            authorWeights[author] = (authorWeights[author] ?: 0.0) + contribution
        }
        for (token in tokenize(v.name)) {
            keywordWeights[token] = (keywordWeights[token] ?: 0.0) + recency
        }
    }
    return InterestProfile(authorWeights, keywordWeights)
}

/**
 * Scores a single candidate. Relevance terms (author affinity, keyword overlap,
 * source recency, and an exploration base so zero-affinity candidates can still
 * surface) are summed, then scaled by upload freshness — mirroring the shape of
 * the original scorer.
 */
fun scoreCandidate(
    candidate: Candidate,
    profile: InterestProfile,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
): Double {
    val v = candidate.video
    val affinity = profile.authorWeights[v.author.lowercase()] ?: 0.0
    val authorScore = minOf(affinity * weights.authorAffinityWeight, weights.authorAffinityCap)

    val keywordScore = tokenize(v.name)
        .sumOf { profile.keywordWeights[it] ?: 0.0 } * weights.keywordWeight

    val sourceRecencyScore = if (candidate.source == RecSource.RELATED && candidate.sourceWatchedAt != null) {
        val hoursAgo = (now - candidate.sourceWatchedAt).inWholeHours.toDouble()
        maxOf(weights.sourceRecencyWeight - hoursAgo / 24.0, 0.0)
    } else {
        0.0
    }

    val uploadAgeDays = (now - v.uploadDate).inWholeDays.toDouble().coerceAtLeast(0.0)
    val freshness = exp(-weights.uploadFreshnessDecay * uploadAgeDays)

    return (authorScore + keywordScore + sourceRecencyScore + weights.explorationBase) * freshness
}

/**
 * Ranks candidates into the final feed.
 *
 * Steps: dedup by video id keeping the max score across sources; drop videos
 * already watched >=90%; demote (not exclude) subscription-authored videos via
 * [RecommendationWeights.subscriptionDamping]; then greedily select with MMR to
 * balance relevance against diversity, enforcing a per-author cap and a
 * subscription-fraction quota on the final feed.
 */
fun rankRecommendations(
    candidates: List<Candidate>,
    history: List<HistoryVideo>,
    subNames: Set<String>,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
): List<VideoInfo> {
    val profile = buildInterestProfile(history, now, weights)
    val historyById = history.associateBy { it.id }

    val best = HashMap<Long, Scored>()
    for (c in candidates) {
        val v = c.video
        val h = historyById[v.videoID]
        if (h != null && v.duration > 0 && h.progress.toDouble() / (v.duration * 1000) >= 0.9) continue

        val isSub = v.author.lowercase() in subNames
        var score = scoreCandidate(c, profile, now, weights)
        if (isSub) score *= weights.subscriptionDamping

        val existing = best[v.videoID]
        if (existing == null || score > existing.score) {
            best[v.videoID] = Scored(v, score, tokenize(v.name).toSet(), isSub)
        }
    }

    if (best.isEmpty()) return emptyList()

    val pool = best.values.sortedByDescending { it.score }.toMutableList()
    val maxScore = pool.first().score.takeIf { it > 0.0 } ?: 1.0

    val resultLimit = minOf(pool.size, weights.maxResults)
    val subQuota = (weights.maxResults * weights.maxSubscriptionFraction).toInt()

    val selected = mutableListOf<Scored>()
    val authorCount = HashMap<String, Int>()
    var subCount = 0

    while (selected.size < resultLimit && pool.isNotEmpty()) {
        var bestIdx = -1
        var bestMmr = Double.NEGATIVE_INFINITY
        for (i in pool.indices) {
            val cand = pool[i]
            val author = cand.video.author.lowercase()
            if ((authorCount[author] ?: 0) >= weights.perAuthorCap) continue
            if (cand.isSub && subCount >= subQuota) continue

            val maxSim = selected.maxOfOrNull { similarity(cand, it) } ?: 0.0
            val relevance = cand.score / maxScore
            val mmr = weights.mmrLambda * relevance - (1 - weights.mmrLambda) * maxSim
            if (mmr > bestMmr) {
                bestMmr = mmr
                bestIdx = i
            }
        }
        if (bestIdx < 0) break

        val chosen = pool.removeAt(bestIdx)
        selected.add(chosen)
        val a = chosen.video.author.lowercase()
        authorCount[a] = (authorCount[a] ?: 0) + 1
        if (chosen.isSub) subCount++
    }

    return selected.map { it.video }
}

private data class Scored(
    val video: VideoInfo,
    val score: Double,
    val tokens: Set<String>,
    val isSub: Boolean,
)

private fun similarity(a: Scored, b: Scored): Double {
    val authorSim = if (a.video.author.equals(b.video.author, ignoreCase = true)) 1.0 else 0.0
    return 0.6 * authorSim + 0.4 * jaccard(a.tokens, b.tokens)
}

private fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.count { it in b }
    val union = a.size + b.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}
