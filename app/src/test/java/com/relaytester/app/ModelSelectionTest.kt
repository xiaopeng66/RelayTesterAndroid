package com.relaytester.app

import com.relaytester.app.feature.fingerprint.filterModels
import com.relaytester.app.feature.fingerprint.toggleModel
import com.relaytester.app.feature.fingerprint.unmatchedKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the picker helpers that decide what the model list shows and in what order.
 *
 * These are the rules the user asked for in words: type a keyword to filter the
 * supplier's models, tick the ones to test, and have them tested in tick order.
 */
class ModelSelectionTest {
    private val catalogue = listOf("gpt-4o", "gpt-4o-mini", "o3-mini", "claude-sonnet-4")

    @Test
    fun `an empty keyword keeps the whole catalogue in its own order`() {
        assertEquals(catalogue, filterModels(catalogue, ""))
        assertEquals(catalogue, filterModels(catalogue, "   "))
    }

    @Test
    fun `the keyword matches anywhere in the name and ignores case`() {
        assertEquals(
            listOf("gpt-4o", "gpt-4o-mini"),
            filterModels(catalogue, "GPT-4O"),
        )
        assertEquals(
            listOf("gpt-4o-mini", "o3-mini"),
            filterModels(catalogue, "mini"),
        )
    }

    @Test
    fun `a keyword matching nothing yields an empty list rather than the whole catalogue`() {
        // Returning the full catalogue here would silently ignore the filter and let
        // the user tick a model they explicitly filtered away.
        assertEquals(emptyList<String>(), filterModels(catalogue, "llama"))
    }

    @Test
    fun `surrounding whitespace in the keyword is ignored`() {
        assertEquals(listOf("o3-mini"), filterModels(catalogue, "  o3  "))
    }

    @Test
    fun `ticking appends so the round follows the tick order, not the catalogue order`() {
        // Ticked from the bottom of the list up: the round must still run z first.
        val first = toggleModel(emptyList(), "o3-mini")
        val second = toggleModel(first, "claude-sonnet-4")
        val third = toggleModel(second, "gpt-4o")

        assertEquals(listOf("o3-mini", "claude-sonnet-4", "gpt-4o"), third)
    }

    @Test
    fun `unticking removes the model and leaves the rest in order`() {
        val selected = listOf("a", "b", "c")

        assertEquals(listOf("a", "c"), toggleModel(selected, "b"))
    }

    @Test
    fun `unticking the only selection leaves nothing selected`() {
        assertEquals(emptyList<String>(), toggleModel(listOf("a"), "a"))
    }

    @Test
    fun `a keyword the catalogue does not carry is offered as a model of its own`() {
        assertEquals("llama-3-70b", unmatchedKeyword(catalogue, "llama-3-70b"))
    }

    @Test
    fun `a keyword already in the catalogue is not offered twice`() {
        assertNull(unmatchedKeyword(catalogue, "gpt-4o"))
        // Case-insensitive, because the catalogue match is: otherwise "GPT-4O" would
        // show up as a second row for the same model.
        assertNull(unmatchedKeyword(catalogue, "GPT-4O"))
    }

    @Test
    fun `a keyword that matched something is not offered as a model name`() {
        // Typing "mini" is a filter, not a request for a model called "mini"; the two
        // rows it matched are the whole answer here.
        assertNull(unmatchedKeyword(catalogue, "mini"))
        assertNull(unmatchedKeyword(catalogue, "4"))
    }

    @Test
    fun `a blank keyword offers nothing extra`() {
        assertNull(unmatchedKeyword(catalogue, ""))
        assertNull(unmatchedKeyword(catalogue, "  "))
    }

    @Test
    fun `a supplier with no catalogue at all still yields a usable row`() {
        // Everything the user types is unmatched, which is the only way such a
        // supplier can be given a model to detect.
        assertEquals("gpt-4o", unmatchedKeyword(emptyList(), "gpt-4o"))
        assertEquals(emptyList<String>(), filterModels(emptyList(), "gpt-4o"))
    }
}
