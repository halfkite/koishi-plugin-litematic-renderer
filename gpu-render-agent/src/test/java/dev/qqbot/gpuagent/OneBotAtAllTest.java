package dev.qqbot.gpuagent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OneBotAtAllTest {
    @Test
    void distinguishesAllTargetFromMemberMention() {
        assertTrue(OneBotAdapter.isAtAllTarget(JsonParser.parseString("{\"qq\":\"all\"}").getAsJsonObject()));
        assertTrue(OneBotAdapter.isAtAllTarget(JsonParser.parseString("{\"qq\":\"ALL\"}").getAsJsonObject()));
        assertFalse(OneBotAdapter.isAtAllTarget(JsonParser.parseString("{\"qq\":\"123456\"}").getAsJsonObject()));
    }

    @Test
    void recognizesRawCqAtAllButNotPlainText() {
        assertTrue(OneBotAdapter.containsAtAllMarker("[CQ:at,qq=all] /帮助"));
        assertTrue(OneBotAdapter.containsAtAllMarker("[CQ:at,name=全体成员,qq=all]"));
        assertFalse(OneBotAdapter.containsAtAllMarker("有人发了 @全体成员 这段文本"));
        assertFalse(OneBotAdapter.containsAtAllMarker("[CQ:at,qq=123456]"));
    }

    @Test
    void preservesAtAllAsSeparateNormalizedMessageState() throws Exception {
        AtomicReference<BotMessage> received = new AtomicReference<>();
        AgentConfig.BotProfile profile = new AgentConfig.BotProfile();
        profile.id = "test";
        try (OneBotAdapter adapter = new OneBotAdapter(profile, (ignored, message) -> received.set(message), ignored -> {})) {
            Method handle = OneBotAdapter.class.getDeclaredMethod("handle", com.google.gson.JsonObject.class);
            handle.setAccessible(true);
            handle.invoke(adapter, JsonParser.parseString("""
                    {"post_type":"message","message_type":"group","message_id":"all-1",
                     "user_id":"user-1","group_id":"group-1","self_id":"bot-1",
                     "raw_message":"[CQ:at,qq=all]/帮助",
                     "message":[{"type":"at","data":{"qq":"all"}},
                                {"type":"text","data":{"text":"/帮助"}}]}
                    """).getAsJsonObject());
        }

        assertNotNull(received.get());
        assertTrue(received.get().mentioned());
        assertTrue(received.get().mentionedAll());
        assertEquals("[CQ:at,qq=all]/帮助", received.get().rawText());
    }
}
