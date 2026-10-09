package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderReadScopeTest {
    @Test void scopeIsNestedAndDoesNotLeakToOtherThreadsOrExports() throws Exception {
        assertFalse(RenderReadScope.active());
        try (RenderReadScope first = RenderReadScope.open()) {
            assertTrue(RenderReadScope.active());
            RenderReadScope.containerSkipped();
            try (RenderReadScope nested = RenderReadScope.open()) { assertTrue(RenderReadScope.active()); }
            assertEquals(1, first.skippedContainers());
            var otherThreadActive = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread thread = new Thread(() -> otherThreadActive.set(RenderReadScope.active()));
            thread.start(); thread.join();
            assertFalse(otherThreadActive.get());
            assertTrue(RenderReadScope.active());
        }
        assertFalse(RenderReadScope.active());
        assertThrows(IllegalStateException.class, () -> {
            try (RenderReadScope ignored = RenderReadScope.open()) { throw new IllegalStateException(); }
        });
        assertFalse(RenderReadScope.active());
    }

    @Test void cacheSignatureChangesWithAppearanceInputs() {
        assertEquals(CacheSignature.digest("game|packs|light"), CacheSignature.digest("game|packs|light"));
        assertNotEquals(CacheSignature.digest("game|packs|light"), CacheSignature.digest("game|new-packs|light"));
        assertNotEquals(CacheSignature.digest("game|packs|light"), CacheSignature.digest("game|packs|new-light"));
    }
}
