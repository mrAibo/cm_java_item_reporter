package com.mraibo.cminsight.security;

import com.mraibo.cminsight.config.AppConfig;

import java.net.InetAddress;

public final class SecurityPolicy {
    private SecurityPolicy() {}

    public static void validateWebExposure(AppConfig config) {
        String bind = config.webBind();
        final boolean loopback;
        try {
            loopback = InetAddress.getByName(bind).isLoopbackAddress();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve web.bind: " + bind, e);
        }

        boolean defaultCredentials = "admin".equals(config.webUser()) && "admin".equals(config.webPassword());
        if (!loopback && defaultCredentials) {
            throw new IllegalStateException("Refusing non-loopback bind with default admin/admin credentials");
        }
        if (config.webUser().isBlank() || config.webPassword().isBlank()) {
            throw new IllegalStateException("Web authentication credentials must not be blank");
        }
    }
}
