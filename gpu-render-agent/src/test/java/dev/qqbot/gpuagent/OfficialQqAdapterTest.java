package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OfficialQqAdapterTest {
    @Test
    void expiredPassiveReplyRetriesTheSameMediaOnceAsAnActiveMessage() throws Exception {
        var original = com.google.gson.JsonParser.parseString("""
                {"msg_id":"expired","msg_seq":2,"msg_type":7,"content":"投影结果",
                 "media":{"file_info":"uploaded-image"}}
                """).getAsJsonObject();
        var attempts = new java.util.ArrayList<com.google.gson.JsonObject>();
        var receipt = new com.google.gson.JsonObject();
        receipt.addProperty("id", "active-result");

        var response = OfficialQqAdapter.postWithActiveFallback(original, body -> {
            attempts.add(body.deepCopy());
            if (attempts.size() == 1) throw new OfficialQqAdapter.QqApiException(400,
                    "{\"message\":\"回复消息msg_id已过期\",\"code\":40034005}");
            return receipt;
        }, ignored -> {});

        assertSame(receipt, response);
        assertEquals(2, attempts.size());
        assertTrue(original.has("msg_id"));
        var active = attempts.get(1);
        assertFalse(active.has("msg_id"));
        assertFalse(active.has("msg_seq"));
        assertEquals("投影结果", active.get("content").getAsString());
        assertEquals("uploaded-image", active.getAsJsonObject("media").get("file_info").getAsString());
    }

    @Test
    void otherApiErrorsAreNotRetried() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("msg_id", "source-message");
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new OfficialQqAdapter.QqApiException(403, "{\"err_code\":403001}");

        assertSame(failure, assertThrows(OfficialQqAdapter.QqApiException.class, () ->
                OfficialQqAdapter.postWithActiveFallback(body, request -> {
                    attempts.incrementAndGet();
                    throw failure;
                }, ignored -> {})));
        assertEquals(1, attempts.get());
    }

    @Test
    void failedActiveFallbackIsNotRetriedAgain() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("msg_id", "expired");
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(OfficialQqAdapter.QqApiException.class, () ->
                OfficialQqAdapter.postWithActiveFallback(body, request -> {
                    attempts.incrementAndGet();
                    throw new OfficialQqAdapter.QqApiException(400, "{\"err_code\":40034005}");
                }, ignored -> {}));
        assertEquals(2, attempts.get());
    }

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
                new BotAction("地图视图", "/地图视图42"),
                new BotAction("搜索投影", "/搜索投影 ", false)));
        assertEquals(2, body.get("msg_type").getAsInt());
        var buttons = body.getAsJsonObject("keyboard").getAsJsonObject("content")
                .getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonArray("buttons");
        assertEquals(3, buttons.size());
        assertEquals("/导出材料42", buttons.get(0).getAsJsonObject().getAsJsonObject("action").get("data").getAsString());
        assertTrue(buttons.get(0).getAsJsonObject().getAsJsonObject("action").get("enter").getAsBoolean());
        var searchButton = body.getAsJsonObject("keyboard").getAsJsonObject("content")
                .getAsJsonArray("rows").get(1).getAsJsonObject().getAsJsonArray("buttons").get(0).getAsJsonObject();
        assertFalse(searchButton.getAsJsonObject("action").get("enter").getAsBoolean());
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
    void detectsOfficialAtAllMarkersWithoutMatchingOrdinaryText() {
        assertTrue(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"mention_everyone\":true}", com.google.gson.JsonObject.class)));
        assertTrue(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"content\":\"<qqbot-at-everyone />\"}", com.google.gson.JsonObject.class)));
        assertTrue(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"content\":\"@everyone /帮助\"}", com.google.gson.JsonObject.class)));
        assertTrue(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"mentions\":[{\"id\":\"all\"}]}", com.google.gson.JsonObject.class)));
        assertFalse(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"content\":\"这段文字提到了@全体成员，但不是提及\"}", com.google.gson.JsonObject.class)));
        assertFalse(OfficialQqAdapter.isAtAllMessage(Protocol.GSON.fromJson(
                "{\"mentions\":[{\"id\":\"member-42\",\"is_you\":false}]}", com.google.gson.JsonObject.class)));
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
