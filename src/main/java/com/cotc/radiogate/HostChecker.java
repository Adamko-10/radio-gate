package com.cotc.radiogate;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks host NAMES up (off the server thread) and remembers whether they point to a public address. A name that resolves to a
 * loopback/LAN/non-public address is refused. Answers are cached for ten minutes.
 */
public final class HostChecker {
    public enum Status { PUBLIC, PRIVATE, UNKNOWN_HOST, PENDING }

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private record Entry(Status status, long at) {
    }

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "radiogate-dns");
        t.setDaemon(true);
        return t;
    });
    private static volatile Resolver resolver = InetAddress::getAllByName;

    private HostChecker() {
    }

    /** Tests plug a fake DNS in here. */
    public static void setResolver(Resolver r) {
        resolver = r == null ? InetAddress::getAllByName : r;
        CACHE.clear();
    }

    public static void clear() {
        CACHE.clear();
    }

    /** The cached answer, or PENDING while a lookup (started by this call if needed) is running. */
    public static Status status(String host) {
        String h = Urls.normalHost(host);
        Entry e = CACHE.get(h);
        if (e != null && System.currentTimeMillis() - e.at() < TTL_MS) {
            return e.status();
        }
        if (RUNNING.add(h)) {
            Resolver r = resolver;
            POOL.execute(() -> {
                Status s;
                try {
                    InetAddress[] all = r.resolve(h);
                    s = Urls.allPublic(all) ? Status.PUBLIC : Status.PRIVATE;
                } catch (Exception ex) {
                    s = Status.UNKNOWN_HOST;
                }
                CACHE.put(h, new Entry(s, System.currentTimeMillis()));
                RUNNING.remove(h);
            });
        }
        return Status.PENDING;
    }
}
