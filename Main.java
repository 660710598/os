import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DistributedProcessSharedMemoryBossElectionSimulation {

    // Simulation configuration constants
    public static final int NUM_PROCESSES = 3;
    public static final int REGION_SIZE = 128;
    public static final String SHARED_FILE = "heartbeat_shared.dat";

    static class SystemState {
        static volatile int bossPid;
        static final long[] lastHeartbeat = new long[NUM_PROCESSES + 1];
        static final int[][] contactCounts = new int[NUM_PROCESSES + 1][NUM_PROCESSES + 1];
        static final boolean[] dead = new boolean[NUM_PROCESSES + 1];

        static synchronized void markDead(int pid) {
            if (!dead[pid]) {
                dead[pid] = true;
                System.out.println("[System] Process " + pid
                        + " is considered dead (no heartbeat for >20s)");
            }
        }

        static synchronized void electNewBoss() {
            int oldBoss = bossPid;
            int newBoss = -1;
            int maxInteractions = -1;
            for (int i = 1; i <= NUM_PROCESSES; i++) {
                if (dead[i]) {
                    continue;
                }
                int total = 0;
                for (int j = 1; j <= NUM_PROCESSES; j++) {
                    total += contactCounts[i][j];
                }
                if (total > maxInteractions) {
                    maxInteractions = total;
                    newBoss = i;
                }
            }
            if (newBoss != -1 && newBoss != bossPid) {
                bossPid = newBoss;
                System.out.println("[System] Boss " + oldBoss
                        + " has died. New boss elected: PID " + newBoss);
            }
        }
    }

    public static final int DEFAULT_WORKER_ITERATIONS = 5;

    public static void main(String[] args) {
        SystemState.bossPid = 1 + (int) (Math.random() * NUM_PROCESSES);
        System.out.println("[System] Initial boss is PID " + SystemState.bossPid);
        long now = System.currentTimeMillis();
        for (int i = 1; i <= NUM_PROCESSES; i++) {
            SystemState.lastHeartbeat[i] = now;
        }

        for (int pid = 1; pid <= NUM_PROCESSES; pid++) {
            SimulatedProcess process = new SimulatedProcess(pid);
            Thread t = new Thread(process, "Process-" + pid);
            t.start();
        }
    }

    static class SimulatedProcess implements Runnable {
        private final int pid;
        private static final int NUM_THREADS_PER_PROCESS = 3;
        private final MappedByteBuffer sharedBuffer;
        private final Set<Integer> membership;

        SimulatedProcess(int pid) {
            this.pid = pid;
            this.membership = ConcurrentHashMap.newKeySet();
            try {
                RandomAccessFile raf = new RandomAccessFile(
                        SHARED_FILE, "rw");
                long size = (long) NUM_PROCESSES * REGION_SIZE;
                if (raf.length() < size) {
                    raf.setLength(size);
                }
                FileChannel channel = raf.getChannel();
                this.sharedBuffer = channel.map(
                        FileChannel.MapMode.READ_WRITE, 0, size);
                channel.close();
                raf.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to map shared heartbeat file", e);
            }
        }

        @Override
        public void run() {
            System.out.println("[Process " + pid + "] starting");

            Thread sender = new Thread(
                    new HeartbeatSender(pid, sharedBuffer),
                    "Process-" + pid + "-HeartbeatSender");
            Thread listener = new Thread(
                    new HeartbeatListener(pid, sharedBuffer,
                            NUM_PROCESSES, membership),
                    "Process-" + pid + "-HeartbeatListener");
            sender.start();
            listener.start();

            Thread detector = new Thread(
                    new FailureDetector(pid),
                    "Process-" + pid + "-FailureDetector");
            detector.start();

            int iterations = DEFAULT_WORKER_ITERATIONS;
            if (pid == SystemState.bossPid) {
                iterations = Math.max(1, DEFAULT_WORKER_ITERATIONS / 2);
            }
            // Start worker threads to simulate computation
            List<Thread> workers = new ArrayList<>();
            for (int tid = 1; tid <= NUM_THREADS_PER_PROCESS; tid++) {
                SimulatedThread worker = new SimulatedThread(pid, tid, iterations);
                Thread wt = new Thread(worker,
                        "Process-" + pid + "-Worker-" + tid);
                workers.add(wt);
                wt.start();
            }

            for (Thread wt : workers) {
                try {
                    wt.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (pid == SystemState.bossPid) {
                System.out.println("[Process " + pid
                        + "] finished its reduced workload and will now relinquish boss role");
                SystemState.markDead(pid);
                SystemState.electNewBoss();
            }

            sender.interrupt();
            listener.interrupt();
            detector.interrupt();
            try {
                sender.join();
                listener.join();
                detector.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[Process " + pid + "] terminating");
        }
    }

    static class SimulatedThread implements Runnable {
        private final int pid;
        private final int tid;
        private final int iterations;

        SimulatedThread(int pid, int tid, int iterations) {
            this.pid = pid;
            this.tid = tid;
            this.iterations = iterations;
        }

        @Override
        public void run() {
            for (int i = 1; i <= iterations; i++) {
                System.out.println("Process " + pid + " - Thread " + tid
                        + " working (iteration " + i + ")");
                try {
                    Thread.sleep((long) (Math.random() * 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("Process " + pid + " - Thread " + tid + " finished");
        }
    }

    static class HeartbeatSender implements Runnable {
        private final int pid;
        private final MappedByteBuffer buffer;

        HeartbeatSender(int pid, MappedByteBuffer buffer) {
            this.pid = pid;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            final int offset = (pid - 1) * REGION_SIZE;
            final byte[] clearBytes = new byte[REGION_SIZE];
            while (!Thread.currentThread().isInterrupted()) {
                String message = "PID:" + pid + " alive";
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                synchronized (buffer) {
                    buffer.position(offset);
                    buffer.put(clearBytes, 0, clearBytes.length);
                    buffer.position(offset);
                    buffer.put(data, 0, Math.min(data.length, REGION_SIZE));
                }

                SystemState.lastHeartbeat[pid] = System.currentTimeMillis();

                System.out.println("Process " + pid + " wrote heartbeat: " + message);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    static class HeartbeatListener implements Runnable {
        private final int pid;
        private final MappedByteBuffer buffer;
        private final int numProcesses;
        private final Set<Integer> membership;

        HeartbeatListener(int pid, MappedByteBuffer buffer, int numProcesses,
                Set<Integer> membership) {
            this.pid = pid;
            this.buffer = buffer;
            this.numProcesses = numProcesses;
            this.membership = membership;
        }

        @Override
        public void run() {
            final byte[] readBytes = new byte[REGION_SIZE];
            while (!Thread.currentThread().isInterrupted()) {
                if (pid == SystemState.bossPid) {
                    membership.clear();
                }
                for (int otherPid = 1; otherPid <= numProcesses; otherPid++) {
                    if (otherPid == pid) {
                        continue;
                    }
                    int offset = (otherPid - 1) * REGION_SIZE;
                    String msg;
                    synchronized (buffer) {
                        buffer.position(offset);
                        buffer.get(readBytes, 0, REGION_SIZE);
                        msg = new String(readBytes, StandardCharsets.UTF_8).trim();
                    }
                    if (!msg.isEmpty()) {
                        SystemState.lastHeartbeat[otherPid] = System.currentTimeMillis();
                        synchronized (SystemState.class) {
                            SystemState.contactCounts[pid][otherPid]++;
                            SystemState.contactCounts[otherPid][pid]++;
                        }
                        if (pid == SystemState.bossPid) {
                            membership.add(otherPid);
                            System.out.println("[Boss " + pid + "] read heartbeat from PID "
                                    + otherPid + ": " + msg);
                        }
                    }
                }
                if (pid == SystemState.bossPid) {
                    System.out.println("[Boss " + pid + "] sees membership: " + membership);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    static class FailureDetector implements Runnable {
        private final int pid;

        FailureDetector(int pid) {
            this.pid = pid;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                for (int i = 1; i <= NUM_PROCESSES; i++) {
                    if (!SystemState.dead[i] && now - SystemState.lastHeartbeat[i] > 20000) {
                        SystemState.markDead(i);
                        if (i == SystemState.bossPid) {
                            SystemState.electNewBoss();
                        }
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
