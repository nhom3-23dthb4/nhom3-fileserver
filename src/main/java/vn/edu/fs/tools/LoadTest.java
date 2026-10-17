package vn.edu.fs.tools;

import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.ChunkListener;
import vn.edu.fs.server.TlsConfig;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test tai: mo nhieu client cung luc (1, 5, 10, 20); moi client dang nhap, upload roi download
 * mot file 1 MB, so sanh noi dung, do thoi gian va in bang ket qua.
 *
 * Chay: bat server truoc (ServerMain), roi chay LoadTest.
 *   Tham so (tuy chon): host port user password
 *   Bien moi truong: FS_KEYSTORE (mac dinh certs/server.p12), FS_KEYSTORE_PASSWORD (bat buoc)
 */
public final class LoadTest {

    private static final int FILE_BYTES = 1024 * 1024;                 // 1 MB moi client

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8443;  // cong mac dinh cua ServerMain
        String user = args.length > 2 ? args[2] : "alice";
        String pass = args.length > 3 ? args[3] : "alice123";           // tai khoan dev: toan quyen

        String pw = System.getenv("FS_KEYSTORE_PASSWORD");
        if (pw == null || pw.isEmpty()) {
            System.err.println("Thieu bien moi truong FS_KEYSTORE_PASSWORD");
            System.exit(2);
        }
        Path ks = Path.of(System.getenv().getOrDefault("FS_KEYSTORE", "certs/server.p12"));
        // Client chi tin chung chi cua server (truststore), KHONG tat kiem tra TLS.
        SSLContext ctx = TlsConfig.clientContext(ks, pw.toCharArray());

        run(1, host, port, user, pass, ctx, false);                      // khoi dong nong JVM/TLS, khong in
        System.out.println("| Client | Thanh cong | TB (ms) | Cham nhat (ms) | MB/s |");
        System.out.println("| --- | --- | --- | --- | --- |");
        for (int clients : new int[]{1, 5, 10, 20}) {
            run(clients, host, port, user, pass, ctx, true);
        }
    }

    private static void run(int clients, String host, int port, String user, String pass, SSLContext ctx, boolean print) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        for (int i = 0; i < clients; i++) {
            final int id = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();                                             // tat ca xuat phat cung luc
                long t0 = System.nanoTime();
                oneClient(id, clients, host, port, user, pass, ctx);
                return (System.nanoTime() - t0) / 1_000_000;            // ms
            }));
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();

        List<Long> times = new ArrayList<>();
        int ok = 0;
        for (Future<Long> f : results) {
            try {
                times.add(f.get(120, TimeUnit.SECONDS));
                ok++;
            } catch (ExecutionException | TimeoutException e) {
                System.err.println("Client loi: " + (e.getCause() != null ? e.getCause() : e));
            }
        }
        long wallMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        pool.shutdownNow();

        Collections.sort(times);
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
        long worst = times.isEmpty() ? 0 : times.get(times.size() - 1);
        double mbPerSec = ok * 2.0 * (FILE_BYTES / (1024.0 * 1024.0)) / (wallMs / 1000.0);   // upload + download
        if (print) System.out.printf("| %d | %d/%d | %.0f | %d | %.2f |%n", clients, ok, clients, avg, worst, mbPerSec);
    }

    /** Mot client: dang nhap, upload file 1 MB, download lai, so sanh, xoa file tren server. */
    private static void oneClient(int id, int clients, String host, int port, String user, String pass, SSLContext ctx) throws Exception {
        byte[] data = new byte[FILE_BYTES];
        new Random(id).nextBytes(data);
        String name = "/load_" + clients + "_" + id + ".bin";            // moi client mot file rieng
        Path up = Files.createTempFile("load-up-", ".bin");
        Path down = Files.createTempFile("load-down-", ".bin");
        try {
            Files.write(up, data);
            try (FileClient c = new FileClient(host, port, ctx)) {
                c.login(user, pass);
                c.upload(name, up, ChunkListener.NONE);
                c.download(name, down, ChunkListener.NONE);
                if (!Arrays.equals(data, Files.readAllBytes(down))) {
                    throw new IllegalStateException("Du lieu khong khop");
                }
                c.delete(name);                                          // don dep
            }
        } finally {
            Files.deleteIfExists(up);
            Files.deleteIfExists(down);
        }
    }
}
