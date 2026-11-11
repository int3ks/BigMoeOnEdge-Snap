package com.bigmoe.onedge.data.local.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPromptProfileTest {
    private val a = SystemPromptProfile("a", "Coder", "You write code.")
    private val b = SystemPromptProfile("b", "Story", "You tell stories.")

    @Test fun encodeDecodeRoundTrip() {
        assertEquals(listOf(a, b), SystemPromptProfile.decode(SystemPromptProfile.encode(listOf(a, b))))
    }

    @Test fun decodeSkipsBrokenAndDuplicateEntries() {
        assertEquals(emptyList<SystemPromptProfile>(), SystemPromptProfile.decode("not json"))
        assertEquals(listOf(a), SystemPromptProfile.decode("""[{"id":"a","name":"Coder","prompt":"You write code."},{"id":"a","name":"x","prompt":"y"},{"name":"no id"}]"""))
    }

    @Test fun oldSinglePromptBecomesDefaultProfile() {
        val list = SystemPromptProfile.resolve(null, "Be brief.")
        assertEquals(listOf(SystemPromptProfile(SystemPromptProfile.LEGACY_ID, "Default", "Be brief.")), list)
        assertEquals(emptyList<SystemPromptProfile>(), SystemPromptProfile.resolve(null, ""))
    }

    @Test fun storedListWinsOverOldPrompt_evenWhenEmpty() {
        assertEquals(listOf(a), SystemPromptProfile.resolve(SystemPromptProfile.encode(listOf(a)), "Be brief."))
        assertEquals(emptyList<SystemPromptProfile>(), SystemPromptProfile.resolve("[]", "Be brief."))
    }

    @Test fun pickFallsBackToFirst() {
        assertEquals(b, SystemPromptProfile.pick(listOf(a, b), "b"))
        assertEquals(a, SystemPromptProfile.pick(listOf(a, b), "gone"))
        assertNull(SystemPromptProfile.pick(emptyList(), "a"))
    }

    @Test fun chatTogglesChoosePromptAndFallBackToDefault() {
        val s = EngineSettings(systemPrompts = listOf(a, b), defaultSystemPromptId = "a", systemPrompt = "You write code.", systemPromptId = "a")
        val own = ChatToggles.from(s).copy(promptId = "b").applyTo(s)
        assertEquals("You tell stories.", own.systemPrompt)
        assertEquals("b", own.systemPromptId)
        assertEquals("You write code.", ChatToggles.from(s).applyTo(s).systemPrompt)
        assertEquals("You write code.", ChatToggles.from(s).copy(promptId = "deleted").applyTo(s).systemPrompt)
    }

    @Test fun chatTogglesKeepPromptThroughEncoding() {
        val s = EngineSettings()
        val t = ChatToggles.from(s).copy(promptId = "b")
        assertEquals("b", ChatToggles.decode(t.encode(), s)?.promptId)
        assertEquals("", ChatToggles.decode("""{"thinking":true}""", s)?.promptId)
    }
}
