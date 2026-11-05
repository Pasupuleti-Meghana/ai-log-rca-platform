package com.logsense.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Random;

/**
 * Generates a large, realistic multi-service log file (Spring Boot 3 format) for performance demos.
 * Pure JDK so it can run as a single-file program:
 * <pre>
 *   java src/test/java/com/logsense/tools/LogGenerator.java target/large.log 1000000
 * </pre>
 * Every transaction spans api-gateway -> order-service -> inventory-service / payment-service. About 1% of
 * transactions fail with a database pool exhaustion cascade, a payment timeout or retry exhaustion.
 */
public final class LogGenerator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private LogGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "target/large.log");
        long lines = args.length > 1 ? Long.parseLong(args[1]) : 1_000_000L;
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        long start = System.nanoTime();
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            generate(w, lines, 42L);
        }
        System.out.printf("Wrote %,d lines (%,d bytes) to %s in %d ms%n", lines, Files.size(out), out.toAbsolutePath(),
                (System.nanoTime() - start) / 1_000_000);
    }

    /** Writes exactly {@code lines} single-line events (no stack traces), deterministic for a given seed. */
    public static void generate(Writer w, long lines, long seed) throws IOException {
        Random random = new Random(seed);
        Instant clock = Instant.parse("2024-10-15T00:00:00Z");
        long written = 0;
        long tx = 0;
        while (written < lines) {
            tx++;
            String trace = hex(random, 8);
            String gw = hex(random, 8);
            String ord = hex(random, 8);
            String inv = hex(random, 8);
            int scenario = random.nextInt(100);
            String[][] events;
            if (scenario == 0) {
                events = new String[][]{
                        {"api-gateway", "INFO", gw, "Routing request to order-service POST /api/orders"},
                        {"order-service", "INFO", ord, "Calling inventory-service to reserve items for order ORD-" + tx},
                        {"inventory-service", "WARN", inv, "HikariPool-1 - Connection pool exhausted (total=10, active=10, idle=0, waiting=" + (20 + random.nextInt(40)) + ")"},
                        {"order-service", "ERROR", ord, "Read timed out calling inventory-service after 5000ms java.net.SocketTimeoutException: Read timed out"},
                        {"api-gateway", "ERROR", gw, "504 GATEWAY_TIMEOUT from order-service"}};
            } else if (scenario == 1) {
                events = new String[][]{
                        {"order-service", "INFO", ord, "Calling payment-service for order ORD-" + tx},
                        {"payment-service", "WARN", inv, "Retry attempt 3/3 for fraud-check-service failed: java.net.ConnectException: Connection refused"},
                        {"order-service", "ERROR", ord, "Payment call failed: 503 SERVICE_UNAVAILABLE from payment-service"}};
            } else {
                int latency = 5 + random.nextInt(80);
                events = new String[][]{
                        {"api-gateway", "INFO", gw, "Routing request to order-service GET /api/orders/ORD-" + tx},
                        {"order-service", "INFO", ord, "Calling inventory-service to check stock"},
                        {"inventory-service", "DEBUG", inv, "Stock lookup for sku SKU-" + random.nextInt(10_000)},
                        {"order-service", "INFO", ord, "Received response from inventory-service status=200 in " + latency + "ms"},
                        {"api-gateway", "INFO", gw, "Received response from order-service status=200 in " + (latency + 10) + "ms"}};
            }
            for (String[] e : events) {
                if (written >= lines) {
                    break;
                }
                clock = clock.plusMillis(1 + random.nextInt(15));
                w.write(TS.format(clock));
                w.write(' ');
                w.write(String.format("%5s", e[1]));
                w.write(" 1 --- [");
                w.write(e[0]);
                w.write("] [nio-exec-");
                w.write(Integer.toString(1 + random.nextInt(20)));
                w.write("] [");
                w.write(trace);
                w.write(',');
                w.write(e[2]);
                w.write("] c.l.demo.Component : ");
                w.write(e[3]);
                w.write('\n');
                written++;
            }
        }
        w.flush();
    }

    private static String hex(Random random, int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        // guarantee at least one letter so ids are never all digits (never mistaken for card numbers)
        return "a" + HEX.formatHex(b).substring(1);
    }
}