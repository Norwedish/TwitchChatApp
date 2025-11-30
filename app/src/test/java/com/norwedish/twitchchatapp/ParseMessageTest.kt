package com.norwedish.twitchchatapp

import org.junit.Test
import org.junit.Assert.*

class ParseMessageTest {
    @Test
    fun parseUserNotice_subscription_parsesCorrectly() {
        val raw = "@badge-info=;badges=subscriber/12;color=#FF0000;display-name=TestUser;emotes=;flags=;id=12345;login=testuser;mod=0;msg-id=sub;msg-param-cumulative-months=1;msg-param-months=0;msg-param-sub-plan=1000;room-id=99999;subscriber=1;system-msg=TestUser\\ssubscribed!;tmi-sent-ts=1660000000000 :tmi.twitch.tv USERNOTICE #testchannel :"

        val debug = ChatMessageParser.debugParseInfo(raw)
        val result = ChatMessageParser.parse(raw)

        assertNotNull("Parser returned null. Debug: $debug", result)
        assertEquals("Unexpected type (debug: $debug)", MessageType.SUBSCRIPTION, result!!.type)
        assertEquals("Unexpected author (debug: $debug)", "TestUser", result.author)
        assertEquals("Unexpected authorLogin (debug: $debug)", "testuser", result.authorLogin)
        // message should include the system message "TestUser subscribed!"
        assert(result.message.contains("subscribed", ignoreCase = true)) { "Message should contain subscription text. Debug: $debug" }
    }
}
