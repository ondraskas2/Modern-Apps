package com.vayunmathur.youpipe.util

import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.ui.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class RecommenderTest {

    private val now = Instant.fromEpochSeconds(1_700_000_000L)

    private fun video(
        id: Long,
        name: String = "",
        author: String = "",
        duration: Long = 0,
        uploadDate: Instant = now,
    ) = VideoInfo(name, id, duration, 0, uploadDate, "", author)

    private fun history(
        video: VideoInfo,
        progress: Long = 0,
        timestamp: Instant = now,
    ) = HistoryVideo(video.videoID, progress, video, timestamp)

    // ===================== buildInterestProfile =====================

    @Test
    fun `author affinity is engagement weighted`() {
        val finished = history(video(1, author = "Finisher", duration = 100), progress = 100_000)
        val abandoned = history(video(2, author = "Abandoner", duration = 100), progress = 1_000)

        val profile = buildInterestProfile(listOf(finished, abandoned), now)

        assertTrue(
            profile.authorWeights.getValue("finisher") > profile.authorWeights.getValue("abandoner"),
        )
    }

    @Test
    fun `author affinity is recency weighted`() {
        val recent = history(video(1, author = "Recent"), timestamp = now)
        val old = history(video(2, author = "Old"), timestamp = now - 28.days)

        val profile = buildInterestProfile(listOf(recent, old), now)

        assertTrue(profile.authorWeights.getValue("recent") > profile.authorWeights.getValue("old"))
    }

    @Test
    fun `duration zero uses neutral completion default`() {
        val imported = history(video(1, author = "Imported", duration = 0), progress = 0, timestamp = now)

        val profile = buildInterestProfile(listOf(imported), now)

        // recency == 1 at now, so weight == neutralCompletion default (0.5).
        assertEquals(0.5, profile.authorWeights.getValue("imported"), 1e-9)
    }

    // ===================== tokenize =====================

    @Test
    fun `tokenize lowercases and strips punctuation and stopwords`() {
        val tokens = tokenize("The Amazing Cats! How-To (2024)")

        assertTrue(tokens.contains("amazing"))
        assertTrue(tokens.contains("cats"))
        assertTrue(tokens.contains("2024"))
        assertFalse(tokens.contains("the"))
        assertFalse(tokens.contains("how"))
        assertFalse(tokens.contains("to"))
    }

    // ===================== scoreCandidate =====================

    @Test
    fun `author affinity raises score`() {
        val profile = buildInterestProfile(
            listOf(history(video(1, author = "Creator", duration = 10), progress = 10_000)),
            now,
        )
        val known = Candidate(video(2, author = "Creator"), RecSource.TRENDING)
        val unknown = Candidate(video(3, author = "Nobody"), RecSource.TRENDING)

        assertTrue(scoreCandidate(known, profile, now) > scoreCandidate(unknown, profile, now))
    }

    @Test
    fun `keyword overlap raises score`() {
        val profile = buildInterestProfile(
            listOf(history(video(1, name = "kotlin programming tutorial"))),
            now,
        )
        val relevant = Candidate(video(2, name = "kotlin news"), RecSource.TRENDING)
        val irrelevant = Candidate(video(3, name = "cooking recipe"), RecSource.TRENDING)

        assertTrue(scoreCandidate(relevant, profile, now) > scoreCandidate(irrelevant, profile, now))
    }

    @Test
    fun `freshness decays with upload age`() {
        val profile = InterestProfile(emptyMap(), emptyMap())
        val fresh = Candidate(video(1, uploadDate = now), RecSource.TRENDING)
        val stale = Candidate(video(2, uploadDate = now - 100.days), RecSource.TRENDING)

        assertTrue(scoreCandidate(fresh, profile, now) > scoreCandidate(stale, profile, now))
    }

    @Test
    fun `exploration base keeps zero affinity candidates above zero`() {
        val profile = InterestProfile(emptyMap(), emptyMap())
        val weights = RecommendationWeights()
        val candidate = Candidate(video(1, uploadDate = now), RecSource.TRENDING)

        val score = scoreCandidate(candidate, profile, now, weights)

        assertEquals(weights.explorationBase, score, 1e-9)
    }

    // ===================== rankRecommendations =====================

    @Test
    fun `dedup keeps a video once across sources`() {
        val candidates = listOf(
            Candidate(video(1, author = "A", uploadDate = now), RecSource.TRENDING),
            Candidate(video(1, author = "A", uploadDate = now), RecSource.SEARCH),
        )

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now)

        assertEquals(1, ranked.size)
    }

    @Test
    fun `videos watched at least ninety percent are filtered out`() {
        val watched = video(1, author = "A", duration = 100)
        val history = listOf(history(watched, progress = 95_000))
        val candidates = listOf(Candidate(watched, RecSource.RELATED, now))

        val ranked = rankRecommendations(candidates, history, emptySet(), now)

        assertTrue(ranked.none { it.videoID == 1L })
    }

    @Test
    fun `subscriptions are demoted but not excluded`() {
        val subCandidate = Candidate(video(1, author = "SubChannel", uploadDate = now), RecSource.SUBSCRIPTION)
        val discoverCandidate = Candidate(video(2, author = "Discover", uploadDate = now), RecSource.TRENDING)
        val subNames = setOf("subchannel")

        val ranked = rankRecommendations(
            listOf(subCandidate, discoverCandidate),
            emptyList(),
            subNames,
            now,
        )

        assertTrue(ranked.any { it.videoID == 1L }) // sub not excluded
        assertTrue(ranked.indexOfFirst { it.videoID == 2L } < ranked.indexOfFirst { it.videoID == 1L })
    }

    @Test
    fun `subscription fraction quota is respected`() {
        val weights = RecommendationWeights(maxResults = 10, maxSubscriptionFraction = 0.3, perAuthorCap = 3)
        val subCandidates = (1L..10L).map {
            Candidate(video(it, author = "Sub$it", uploadDate = now), RecSource.SUBSCRIPTION)
        }
        val subNames = (1L..10L).map { "sub$it" }.toSet()

        val ranked = rankRecommendations(subCandidates, emptyList(), subNames, now, weights)

        assertEquals(3, ranked.size) // floor(10 * 0.3)
    }

    @Test
    fun `per author cap limits domination`() {
        val weights = RecommendationWeights(perAuthorCap = 3)
        val dominant = (1L..6L).map {
            Candidate(video(it, author = "Loud", uploadDate = now), RecSource.TRENDING)
        }
        val others = (7L..9L).map {
            Candidate(video(it, author = "Other$it", uploadDate = now), RecSource.TRENDING)
        }

        val ranked = rankRecommendations(dominant + others, emptyList(), emptySet(), now, weights)

        assertEquals(3, ranked.count { it.author == "Loud" })
    }
}
