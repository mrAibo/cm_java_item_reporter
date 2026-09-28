package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.security.SecurityPolicy;
import com.mraibo.cminsight.web.WebServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Main() {}

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("conf", "application.properties");
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = Path.of(args[++i]);
            } else if ("--version".equals(args[i])) {
                System.out.println("CM Insight " + VERSION);
                return;
            } else if ("--self-test".equals(args[i])) {
                SelfTest.main(new String[0]);
                return;
            }
        }

        AppConfig config = AppConfig.load(configPath);
        SecurityPolicy.validateWebExposure(config);

        CountDownLatch shutdown = new CountDownLatch(1);
        WebServer server = new WebServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } finally {
                shutdown.countDown();
            }
        }, "cm-insight-shutdown"));

        server.start();
        System.out.printf("CM Insight %s listening on http://%s:%d%n",
                VERSION, config.webBind(), config.webPort());
        shutdown.await();
    }
}
