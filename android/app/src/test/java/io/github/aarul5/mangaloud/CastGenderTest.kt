package io.github.aarul5.mangaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastGenderTest {
    private val gender = SeriesCast.Gender

    @Test
    fun aWomanUnderAMansLabelIsCaught() {
        assertTrue(gender.differ("man", "woman"))
        assertTrue(gender.differ("girl", "old man"))
        assertFalse(gender.differ("man", "boy"))
        assertFalse(gender.differ("man", "unknown"))
        assertFalse(gender.differ("woman", "creature"))
    }

    @Test
    fun aLookOfTheOtherGenderIsDropped() {
        // The look a side character's episode left on "hero".
        assertTrue(gender.lookDiffers("girl with orange twin tails and red ribbons", "man"))
        assertFalse(gender.lookDiffers("young man, spiky white hair", "man"))
        assertFalse(gender.lookDiffers("woman with short black bob hair", "woman"))
        assertTrue(gender.lookDiffers("young man in apartment", "woman"))
        assertFalse(gender.lookDiffers("spiky blue hair", "girl"))
    }
}
