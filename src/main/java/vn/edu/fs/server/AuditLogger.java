package vn.edu.fs.server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Ghi log kiem toan qua mot luong rieng, de khong lam cham luong xu ly. KHONG bao gio ghi mat khau. */
public final class AuditLogger implements AutoCloseable {
    private static final String POISON = "\u0000STOP";
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(10_000);
    private final Thread writer;

    public AuditLogger(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writer = new Thread(() -> {
            try (w) {
                String line;
                while (!(line = queue.take()).equals(POISON)) {
                    w.write(line); w.newLine();
                    if (queue.isEmpty()) w.flush();
                }
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        }, "audit-writer");
        writer.start();
    }

    /** result: "OK", "DENY" hoac "ERR <ma>". */
    public void log(String user, String ip, String command, String path, String result) {
        String line = String.join("\t", Instant.now().toString(), clean(user), clean(ip),
                clean(command), clean(path), clean(result));
        queue.offer(line); // day hang doi -> bo log con hon la lam treo server
    }

    private static String clean(String s) {
        return s == null ? "-" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    @Override public void close() {
        try { queue.put(POISON); writer.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
