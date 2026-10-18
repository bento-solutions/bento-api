package com.bento.crm.push;

import java.util.Map;

/** Delivers one push message to one device. */
public interface PushSender {

    enum Result { SENT, INVALID_TOKEN, FAILED }

    boolean configured();

    Result send(String deviceToken, String title, String body, Map<String, String> data);
}
