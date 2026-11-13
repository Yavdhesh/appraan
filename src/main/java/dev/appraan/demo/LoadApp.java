package dev.appraan.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo target for Appraan: a small JVM with recognisable behaviour —
 * a CPU burner, an allocator that forces young GCs, a sleeping thread and a
 * lock-contended pair, all on named threads. Prints its PID at startup.
 *
 * <p>Run: {@code java -Xmx256m -XX:+UseG1GC -cp dist/appraan.jar dev.appraan.demo.LoadApp}
 */
public final class LoadApp {

    private static final Object LOCK = new Object();
    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("LoadApp started, pid=" + ProcessHandle.current().pid());
        System.out.flush();

        Thread burner = new Thread(LoadApp::burnCpu, "cpu-burner");
        Thread allocator = new Thread(LoadApp::churnAllocations, "allocator");
        Thread sleeper = new Thread(LoadApp::mostlySleep, "sleeper");
        Thread lockA = new Thread(() -> contend("A"), "lock-contender-a");
        Thread lockB = new Thread(() -> contend("B"), "lock-contender-b");

        for (Thread t : List.of(burner, allocator, sleeper, lockA, lockB)) {
            t.setDaemon(false);
            t.start();
        }
        Thread.currentThread().join();
    }

    /** Pure CPU work: trial-division prime checking on ever-growing candidates. */
    private static void burnCpu() {
        long candidate = 1_000_000_007L;
        long primes = 0;
        while (running) {
            if (isPrime(candidate)) {
                primes++;
            }
            candidate += 2;
            if (candidate < 0) {
                candidate = 1_000_000_007L;
            }
        }
        System.out.println("burner found " + primes + " primes");
    }

    static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n % 2 == 0) return n == 2;
        for (long d = 3; d * d <= n; d += 2) {
            if (n % d == 0) return false;
        }
        return true;
    }

    /** Allocation churn: short- and medium-lived objects to provoke frequent young GCs. */
    private static void churnAllocations() {
        List<byte[]> young = new ArrayList<>();
        Map<String, byte[]> medium = new HashMap<>();
        long round = 0;
        while (running) {
            for (int i = 0; i < 200; i++) {
                young.add(new byte[8 * 1024]);
            }
            young.clear();
            medium.put("key-" + (round % 64), new byte[32 * 1024]);
            if (medium.size() > 64) {
                medium.clear();
            }
            round++;
            sleepQuietly(10);
        }
    }

    /** Off-CPU most of the time. */
    private static void mostlySleep() {
        while (running) {
            sleepQuietly(500);
        }
    }

    /** Two threads fighting over one monitor. */
    private static void contend(String id) {
        long counter = 0;
        while (running) {
            synchronized (LOCK) {
                counter++;
                sleepQuietly(2);
            }
            if (counter == Long.MAX_VALUE) {
                System.out.println(id + " overflowed");
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
