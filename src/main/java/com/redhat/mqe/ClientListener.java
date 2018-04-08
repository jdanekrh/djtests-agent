package com.redhat.mqe;

import java.util.Map;

public interface ClientListener {
    void onMessage(Map<String, Object> map);

    void onError(String s);
}
