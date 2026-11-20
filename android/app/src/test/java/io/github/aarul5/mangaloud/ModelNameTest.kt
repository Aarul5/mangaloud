package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNameTest {
    @Test
    fun claudeModelIdsBecomeShortNames() {
        assertEquals("Sonnet 5.5", ModelName.short("claude-sonnet-5-5"))
        assertEquals("Haiku 4.5", ModelName.short("claude-haiku-4-5-20251001"))
        assertEquals("Opus 5", ModelName.short("claude-opus-5"))
    }

    @Test
    fun otherNamesAreKept() {
        assertEquals("Sonnet", ModelName.short("sonnet"))
        assertEquals("Default model", ModelName.short("default model"))
    }
}
