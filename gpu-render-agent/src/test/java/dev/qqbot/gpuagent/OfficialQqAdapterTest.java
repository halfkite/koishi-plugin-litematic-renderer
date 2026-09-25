package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OfficialQqAdapterTest {
    @Test
    void keepsMediaSeparateFromTheClickableKeyboardCard() {
        var media = new com.google.gson.JsonObject();
        media.addProperty("file_info", "uploaded-image");
        var image = OfficialQqAdapter.mediaResultPayload(media, "");
        var card = OfficialQqAdapter.actionCardPayload("投影编号：42", java.util.List.of(
                new BotAction("导出材料", "/导出材料42"),
                new BotAction("更多视图", "/更多视图42"),
                new BotAction("地图视图", "/地图视图42")));

        assertEquals(7, image.get("msg_type").getAsInt());
        assertEquals("uploaded-image", image.getAsJsonObject("media").get("file_info").getAsString());
        assertFalse(image.has("keyboard"));
        assertEquals(2, card.get("msg_type").getAsInt());
        assertEquals("投影编号：42", card.getAsJsonObject("markdown").get("content").getAsString());
        assertEquals(3, card.getAsJsonObject("keyboard").getAsJsonObject("content")
                .getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonArray("buttons").size());
        assertFalse(card.has("media"));
    }

    @Test
    void buildsCommandButtonsWithStableProjectionOrdinals() {
        var body = OfficialQqAdapter.actionCardPayload("投影编号：42", java.util.List.of(
                new BotAction("导出材料", "/导出材料42"),
                new BotAction("更多视图", "/更多视图42"),
                new BotAction("地图视图", "/地图视图42")));
        assertEquals(2, body.get("msg_type").getAsInt());
        var buttons = body.getAsJsonObject("keyboard").getAsJsonObject("content")
                .getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonArray("buttons");
        assertEquals(3, buttons.size());
        assertEquals("/导出材料42", buttons.get(0).getAsJsonObject().getAsJsonObject("action").get("data").getAsString());
        assertTrue(buttons.get(0).getAsJsonObject().getAsJsonObject("action").get("enter").getAsBoolean());
    }

    @Test
    void includesProjectionFileNameInOfficialUploadBody() {
        var body = OfficialQqAdapter.mediaUploadBody(new byte[] {1, 2, 3}, 4, "铁合块.litematic");

        assertEquals(4, body.get("file_type").getAsInt());
        assertEquals("铁合块.litematic", body.get("file_name").getAsString());
        assertEquals("AQID", body.get("file_data").getAsString());
        assertFalse(body.get("srv_send_msg").getAsBoolean());
    }

    @Test
    void omitsOptionalNameForImages() {
        var body = OfficialQqAdapter.mediaUploadBody(new byte[] {1}, 1, null);

        assertFalse(body.has("file_name"));
    }

    @Test
    void usesPassiveMessageContextWithoutAddingVisibleQuote() {
        var message = new BotMessage("account", "source-message", "user", "group", "bot",
                false, false, java.util.List.of(), "file.litematic");
        var first = new com.google.gson.JsonObject();
        var second = new com.google.gson.JsonObject();

        OfficialQqAdapter.addPassiveReply(first, message);
        OfficialQqAdapter.addPassiveReply(second, message);

        assertEquals("source-message", first.get("msg_id").getAsString());
        assertEquals(1, first.get("msg_seq").getAsInt());
        assertEquals(2, second.get("msg_seq").getAsInt());
        assertFalse(first.has("message_reference"));
    }

    @Test
    void detectsBotMentionInFullGroupMessageEvents() {
        var message = com.google.gson.JsonParser.parseString("""
                {"mentions":[{"id":"bot-open-id","is_you":true}]}
                """).getAsJsonObject();

        assertTrue(OfficialQqAdapter.isMentioned("GROUP_MESSAGE_CREATE", message, "bot-open-id"));
    }

    @Test
    void detectsTruthyStringMentionFlags() {
        var message = com.google.gson.JsonParser.parseString("""
                {"mentions":[{"id":"bot-open-id","is_you":"1"}]}
                """).getAsJsonObject();

        assertTrue(OfficialQqAdapter.isMentioned("GROUP_MESSAGE_CREATE", message, ""));
    }

    @Test
    void doesNotTreatMentionOfAnotherMemberAsBotMention() {
        var message = com.google.gson.JsonParser.parseString("""
                {"mentions":[{"id":"other-open-id","is_you":false}]}
                """).getAsJsonObject();

        assertFalse(OfficialQqAdapter.isMentioned("GROUP_MESSAGE_CREATE", message, "bot-open-id"));
    }

    @Test
    void groupAtEventIsAValidBotMentionEvenWithoutMentionPayload() {
        assertTrue(OfficialQqAdapter.isMentioned("GROUP_AT_MESSAGE_CREATE", new com.google.gson.JsonObject(), ""));
    }

    @Test
    void readsOfficialGroupQuoteIndexFromMessageSceneExtensions() {
        var event = com.google.gson.JsonParser.parseString("""
                {"message_scene":{"ext":["msg_idx=REFIDX_CURRENT","ref_msg_idx=REFIDX_RENDER"]}}
                """).getAsJsonObject();

        assertEquals("REFIDX_RENDER", OfficialQqAdapter.referencedMessageId(event));
        assertEquals(java.util.List.of("REFIDX_CURRENT"), OfficialQqAdapter.messageIndexAliases(event));
    }

    @Test
    void doesNotMistakeCurrentMessageIndexForQuotedMessageIndex() {
        var event = com.google.gson.JsonParser.parseString("""
                {"message_scene":{"ext":["msg_idx=REFIDX_CURRENT"]}}
                """).getAsJsonObject();

        assertEquals("", OfficialQqAdapter.referencedMessageId(event));
    }

    @Test
    void readsMessageIndicesFromOfficialSendResponses() {
        var response = com.google.gson.JsonParser.parseString("""
                {"id":"robot-message-id","message_scene":{"ext":["msg_idx=REFIDX_RENDER"]}}
                """).getAsJsonObject();

        assertEquals(java.util.List.of("robot-message-id", "REFIDX_RENDER"),
                OfficialQqAdapter.responseMessageAliases(response));
    }

    @Test
    void matchesQuotedRenderToOfficialSendReceiptReferenceIndex() {
        var response = com.google.gson.JsonParser.parseString("""
                {"id":"ROBOT1.0_render","ext_info":{"ref_idx":"REFIDX_RENDER"}}
                """).getAsJsonObject();
        var event = com.google.gson.JsonParser.parseString("""
                {"id":"user-command","message_scene":{"ext":["msg_idx=REFIDX_COMMAND","ref_msg_idx=REFIDX_RENDER"]}}
                """).getAsJsonObject();
        var aliases = OfficialQqAdapter.responseMessageAliases(response);
        assertEquals(java.util.List.of("ROBOT1.0_render", "REFIDX_RENDER"), aliases);
        assertTrue(aliases.contains(OfficialQqAdapter.referencedMessageId(event)));
        assertFalse(aliases.contains("REFIDX_COMMAND"));
    }

    @Test
    void readsNestedSendReceiptAndIgnoresEmptyReferenceIndex() {
        var response = com.google.gson.JsonParser.parseString("""
                {"ext_info":{"ref_idx":""},"data":{"id":"render","ext_info":{"ref_idx":"REFIDX_RENDER"}}}
                """).getAsJsonObject();
        assertEquals(java.util.List.of("render", "REFIDX_RENDER"),
                OfficialQqAdapter.responseMessageAliases(response));
    }
}
