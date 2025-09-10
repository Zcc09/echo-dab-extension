package dev.brahmkshatriya.echo.common.models

import dev.brahmkshatriya.echo.common.clients.LyricsClient
import kotlinx.serialization.Serializable

/**
 * Represents lyrics of a song, can be loaded later in [LyricsClient.loadLyrics].
 *
 * @property id the unique identifier of the lyrics.
 * @property title the title of the lyrics.
 * @property subtitle the subtitle of the lyrics.
 * @property lyrics the lyrics of the song.
 * @property extras additional information about the lyrics.
 *
 * @see Lyric
 */
@Serializable
data class Lyrics(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val lyrics: Lyric? = null,
    val extras: Map<String, String> = emptyMap()
) {

    /**
     * Represents a lyric of a song.
     *
     * This can be a [Simple] lyric or a [Timed] lyric.
     *
     * @see Simple
     * @see Timed
     */
    @Serializable
    sealed class Lyric {
        companion object {
            /**
             * Create a Lyric from plain text. Supports simple plain text and basic LRC-style timestamps
             * in the form [mm:ss] or [mm:ss.xxx]. If timestamps are found this will return a Timed lyric,
             * otherwise a Simple lyric.
             */
            fun fromText(text: String): Lyric {
                val trimmed = text.trim()
                val lrcRegex = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?](.*)")
                val matches = lrcRegex.findAll(trimmed).toList()
                if (matches.isNotEmpty()) {
                    val parsed = matches.map { m ->
                        val min = m.groupValues[1].toLong()
                        val sec = m.groupValues[2].toLong()
                        val msStr = m.groupValues.getOrNull(3) ?: ""
                        val ms = when {
                            msStr.isEmpty() -> 0L
                            msStr.length == 1 -> (msStr.toLong() * 100)
                            msStr.length == 2 -> (msStr.toLong() * 10)
                            else -> msStr.padEnd(3, '0').take(3).toLong()
                        }
                        val start = min * 60_000 + sec * 1000 + ms
                        val textPart = m.groupValues[4].trim()
                        Lyrics.Item(textPart, start, start + 3000)
                    }.sortedBy { it.startTime }

                    val items = parsed.mapIndexed { i, item ->
                        if (i < parsed.size - 1) {
                            item.copy(endTime = parsed[i + 1].startTime)
                        } else {
                            item
                        }
                    }

                    return Lyrics.Timed(items)
                }

                return Lyrics.Simple(trimmed)
            }
        }
    }

    /**
     * Represents a simple lyric of a song.
     *
     * @property text the text of the lyric.
     */
    @Serializable
    data class Simple(val text: String) : Lyric()

    /**
     * Represents a timed lyric of a song.
     *
     * @property list the list of timed lyric items.
     * @property fillTimeGaps whether to fill the time gaps between the items.
     *
     * @see Item
     */
    @Serializable
    data class Timed(
        val list: List<Item>,
        val fillTimeGaps: Boolean = true
    ) : Lyric()

    /**
     * Represents a word-by-word lyric of a song.
     *
     * @property list the list of lists of timed lyric items, where each inner list represents a word.
     * @property fillTimeGaps whether to fill the time gaps between the items.
     *
     * @see Item
     */
    @Serializable
    data class WordByWord(
        val list: List<List<Item>>,
        val fillTimeGaps: Boolean = true
    ) : Lyric()

    /**
     * Represents a timed lyric item.
     *
     * @property text the text of the lyric.
     * @property startTime the start time of the lyric.
     * @property endTime the end time of the lyric.
     */
    @Serializable
    data class Item(
        val text: String,
        val startTime: Long,
        val endTime: Long
    )
}

