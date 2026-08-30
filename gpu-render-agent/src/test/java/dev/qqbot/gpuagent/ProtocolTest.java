package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test
    void normalizesFabricMinecraftEmulationProperty() {
        assertEquals("-DFabricMcEmu=net.minecraft.client.main.Main",
                RuntimeManager.normalizeJvmArgument("-DFabricMcEmu= net.minecraft.client.main.Main "));
    }

    @Test void authenticatesWithChallengeAndAgentId() {
        String signature = Protocol.hmac("a-secret-at-least-32-characters-long", "challenge.node-1");
        assertTrue(Protocol.secureHexEquals(signature, signature));
        assertFalse(Protocol.secureHexEquals(signature, Protocol.hmac("other", "challenge.node-1")));
    }

    @Test void roundTripsBinaryAttachments() {
        ByteBuffer encoded = Protocol.binary("image", "task-1", "view-1", "view.png", 800, 600, new byte[] {1,2,3});
        Protocol.BinaryFrame decoded = Protocol.decodeBinary(encoded);
        assertEquals("task-1", decoded.header().get("taskId").getAsString());
        assertEquals(800, decoded.header().get("width").getAsInt());
        assertArrayEquals(new byte[] {1,2,3}, decoded.payload());
    }

    @Test void rejectsMalformedBinaryAttachments() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.decodeBinary(ByteBuffer.wrap(new byte[] {0,0,0,100,1})));
    }
}
