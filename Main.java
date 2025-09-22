import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DistributedProcessMain {

    public static final int NUM_PROCESSES = 3;
    public static final int REGION_SIZE = 128;
    public static final String SHARED_FILE = "heartbeat_shared.dat"; // ไฟล์ ตัวกลาง Process ใช้รวมกัน
    public static final long TIMEOUT = 20000; // 20 วินาที

    // ---------- Shared State ----------
    static class SystemState {
        static volatile int bossPid = -1;
        static final long[] lastHeartbeat = new long[NUM_PROCESSES + 1];
        static final int[][] contactCounts = new int[NUM_PROCESSES + 1][NUM_PROCESSES + 1];
        static final boolean[] dead = new boolean[NUM_PROCESSES + 1];
        static final Set<Integer> membership = ConcurrentHashMap.newKeySet(); //thread-safe
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java DistributedProcessMain <pid>");
            System.exit(1);
        }
        int pid = Integer.parseInt(args[0]);
        new DistributedProcess(pid).start(); //สร้าง Process
    }

    // ---------- DistributedProcess ----------
    static class DistributedProcess {
        private final int pid;
        private final MappedByteBuffer buffer;
        private static final int NUM_THREADS_PER_PROCESS = 3;

        DistributedProcess(int pid) {
            this.pid = pid;
            try {
                RandomAccessFile raf = new RandomAccessFile(SHARED_FILE, "rw"); // heartbeat_sheared.dat
                long size = (long) (NUM_PROCESSES + 1) * REGION_SIZE; // slot 0 Boss
                if (raf.length() < size)
                    raf.setLength(size);
                FileChannel channel = raf.getChannel();
                this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);// ไฟล์ หน่วยความจำเดียวกัน
                channel.close();
                raf.close();
            } catch (IOException e) {
                throw new RuntimeException("ไม่สามารถ map shared memory ได้", e);
            }
        }

        void start() {
            System.out.println("[Process " + pid + "] started.");

            int boss = readBossFromSharedMemory(buffer);
            if (boss == -1) {
                int chosen = 1; // fix bossPid = 1
                writeBossToSharedMemory(buffer, chosen);
                SystemState.bossPid = chosen;
                System.out.println("[INIT] Boss fixed to PID " + chosen);
            } else {
                SystemState.bossPid = boss;
                System.out.println("[Process " + pid + "] recognized Boss = PID " + boss);
            }

            Thread sender = new Thread(new HeartbeatSender(pid, buffer), "Sender-" + pid);// ส่งข้อความ
            Thread listener = new Thread(new HeartbeatListener(pid, buffer), "Listener-" + pid);// อ่าร heartbeat
            Thread failureDetector = new Thread(new FailureDetector(pid, buffer), "FailureDetector-" + pid);//เช็ค P ไหนตาย

            sender.start();
            listener.start();
            failureDetector.start();

            List<Thread> workers = new ArrayList<>();
            for (int tid = 1; tid <= NUM_THREADS_PER_PROCESS; tid++) {
                SimulatedThread worker = new SimulatedThread(pid, tid);
                Thread wt = new Thread(worker, "Worker-" + pid + "-" + tid);
                workers.add(wt);
                wt.start();
            }

            for (Thread wt : workers) {
                try {
                    wt.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }//รอ worker หยุด

            sender.interrupt();
            listener.interrupt();
            failureDetector.interrupt();
            //หยุด
        }
    }

    // ---------- Worker ----------
    static class SimulatedThread implements Runnable {
        private final int pid;
        private final int tid;

        SimulatedThread(int pid, int tid) {
            this.pid = pid;
            this.tid = tid;
        }

        @Override
        public void run() {
            for (int i = 1;; i++) {
                System.out.println("[Worker | Process " + pid + " - Thread " + tid + "] working (iteration " + i + ")");
                try {
                    Thread.sleep((long) (500 + Math.random() * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("[Worker | Process " + pid + " - Thread " + tid + "] finished.");
        }
    }

    // ---------- Heartbeat Sender ----------
    static class HeartbeatSender implements Runnable {
        private final int pid;
        private final MappedByteBuffer buffer;

        HeartbeatSender(int pid, MappedByteBuffer buffer) {
            this.pid = pid;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            int offset = pid * REGION_SIZE;
            byte[] clearBytes = new byte[REGION_SIZE];

            while (!Thread.currentThread().isInterrupted()) {
                String msg = "PID:" + pid + " alive " + System.currentTimeMillis();
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                synchronized (buffer) {
                    buffer.position(offset);
                    buffer.put(clearBytes, 0, clearBytes.length);
                    buffer.position(offset);
                    buffer.put(data, 0, Math.min(data.length, REGION_SIZE));
                }
                SystemState.lastHeartbeat[pid] = System.currentTimeMillis();
                SystemState.dead[pid] = false;
                System.out.println("[PID " + pid + "] wrote heartbeat");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // ---------- Heartbeat Listener ----------
    static class HeartbeatListener implements Runnable {
        private final int pid;
        private final MappedByteBuffer buffer;

        HeartbeatListener(int pid, MappedByteBuffer buffer) {
            this.pid = pid;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            byte[] readBytes = new byte[REGION_SIZE];
            while (!Thread.currentThread().isInterrupted()) {
                for (int otherPid = 1; otherPid <= NUM_PROCESSES; otherPid++) {
                    if (otherPid == pid)
                        continue;
                    int offset = otherPid * REGION_SIZE;
                    String msg;
                    synchronized (buffer) {
                        buffer.position(offset);
                        buffer.get(readBytes, 0, REGION_SIZE);
                        msg = new String(readBytes, StandardCharsets.UTF_8).trim();
                    }
                    if (!msg.isEmpty()) {
                        long ts = parseHeartbeatTimestamp(msg);
                        if (ts > 0) {
                            long diff = System.currentTimeMillis() - ts;
                            if (diff <= TIMEOUT) { //  heartbeat ยัง fresh อยู่
                                SystemState.lastHeartbeat[otherPid] = ts;
                                SystemState.contactCounts[pid][otherPid]++;
                                SystemState.dead[otherPid] = false;
                                System.out.println(
                                        "[PID " + pid + "] saw heartbeat from " + otherPid + " (ts=" + ts + ")");
                            } else {
                                //  heartbeat เก่าเกินไป
                                System.out.println("[PID " + pid + "] not saw heartbeat from "
                                        + otherPid + " (last ts=" + ts + ")");
                            }
                        }
                    }

                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // ---------- Failure Detector ----------
    static class FailureDetector implements Runnable {
        private final int pid;
        private final MappedByteBuffer buffer;
        private final long startTime = System.currentTimeMillis();
        private static final long GRACE_PERIOD = 10000; // 10 วินาที ไม่ detect boss ตาย
        private static final long FORCE_BOSS1_PERIOD = 15000; // 15 วินาทีแรก fix Boss=1

        FailureDetector(int pid, MappedByteBuffer buffer) {
            this.pid = pid;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                int currentBoss = readBossFromSharedMemory(buffer);
                SystemState.bossPid = currentBoss;

                if (currentBoss == pid) {
                    // ถ้า process นี้คือ Boss → ทำหน้าที่เช็ค membership
                    SystemState.membership.clear();
                    SystemState.membership.add(pid);// เพิ่มตัวเองลงไปก่อน

                    System.out.println("\n[Boss " + pid + "] Checking membership.");
                    for (int otherPid = 1; otherPid <= NUM_PROCESSES; otherPid++) {
                        if (otherPid == pid)
                            continue;// ข้ามตัวเอง
                        long last = SystemState.lastHeartbeat[otherPid];// เวลาheartbeat ล่าสุด
                        long diff = System.currentTimeMillis() - last;//อายุของ heartbeat
                        if (last > 0 && diff <= TIMEOUT) {
                             if (!SystemState.dead[otherPid]) {  
                                SystemState.membership.add(otherPid);
                                System.out.println("[Boss " + pid + "] PID " + otherPid + " ALIVE (" + diff + " ms)");
                             }
                        } else if (last > 0 && diff > TIMEOUT) {
                            if (!SystemState.dead[otherPid]) { //processยังไม่ถูก mark ตาย
                                SystemState.dead[otherPid] = true;
                                System.out.println("[Boss " + pid + "] PID " + otherPid + " DEAD (" + diff + " ms)");
                            } else {
                                System.out.println(
                                        "[Boss " + pid + "] PID " + otherPid + " still DEAD (" + diff + " ms)");
                            }
                        }
                    }
                    System.out.println("[Boss " + pid + "] Membership = " + SystemState.membership + "\n");

                } else {
                    long ts = readHeartbeatTimestamp(buffer, currentBoss);
                    long age = (ts == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ts);

                    //  บังคับ Boss=1 ช่วง 15 วินาทีแรก
                    if (System.currentTimeMillis() - startTime < FORCE_BOSS1_PERIOD) {
                        SystemState.bossPid = 1;
                        writeBossToSharedMemory(buffer, 1);
                    }
                    //  หลังจากนั้นถ้า Boss ปัจจุบันตาย → เลือกใหม่ตาม contactCounts
                    else if (age > TIMEOUT && System.currentTimeMillis() - startTime > GRACE_PERIOD) {
                        System.out.println("[PID " + pid + "] Boss " + currentBoss + " died! Electing...");
                        int newBoss = electNewBoss();
                        writeBossToSharedMemory(buffer, newBoss);
                        SystemState.bossPid = newBoss;
                        System.out.println(
                                " ---------------- [Election Result] New Boss = PID " + newBoss + " ---------------- ");
                    }
                }

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private int electNewBoss() {
            int maxContact = -1, chosen = -1;
            for (int i = 1; i <= NUM_PROCESSES; i++) {
                if (SystemState.dead[i])
                    continue;
                int sum = 0;
                for (int j = 1; j <= NUM_PROCESSES; j++) {
                    sum += SystemState.contactCounts[i][j];
                }
                if (sum > maxContact) {
                    maxContact = sum;
                    chosen = i;
                }
            }
            return chosen == -1 ? 1 : chosen;
        }

    }

    // ---------- Utility ----------
     private static void writeBossToSharedMemory(MappedByteBuffer buffer, int bossPid) {
        synchronized (buffer) {
            // Boss จะถูกเขียนลง slot 0 เสมอ
            buffer.position(0);

            // ข้อความที่ใช้เก็บ Boss เช่น "BOSS:2"
            String msg = "BOSS:" + bossPid;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);

            // เคลียร์ค่าเก่าทั้ง slot (ใส่ byte[] ที่เต็มด้วยศูนย์)
            buffer.put(new byte[REGION_SIZE], 0, REGION_SIZE);

            // เขียนข้อความ "BOSS:x" กลับไปใหม่
            buffer.position(0);
            buffer.put(data, 0, Math.min(data.length, REGION_SIZE));
        }
    }

    private static int readBossFromSharedMemory(MappedByteBuffer buffer) {
        byte[] readBytes = new byte[REGION_SIZE];
        synchronized (buffer) {
            // อ่าน slot 0 ของ shared memory
            buffer.position(0);
            buffer.get(readBytes, 0, REGION_SIZE);
        }

        // แปลง byte[] → String
        String msg = new String(readBytes, StandardCharsets.UTF_8).trim();

        // ถ้าเจอข้อความขึ้นต้นด้วย "BOSS:" → ดึงเลข PID ออกมา
        if (msg.startsWith("BOSS:")) {
            return Integer.parseInt(msg.split(":")[1]);
        }
        // ถ้าไม่มี boss → return -1
        return -1;
    }

    private static long readHeartbeatTimestamp(MappedByteBuffer buffer, int pid) {
        byte[] arr = new byte[REGION_SIZE];
        synchronized (buffer) {
            // offset ของ process = pid * REGION_SIZE
            buffer.position(pid * REGION_SIZE);
            buffer.get(arr, 0, REGION_SIZE);
        }

        // แปลง byte[] → String
        String s = new String(arr, StandardCharsets.UTF_8).trim();

        // ดึง timestamp จากข้อความ heartbeat
        return parseHeartbeatTimestamp(s);
    }

    private static long parseHeartbeatTimestamp(String msg) {
        // heartbeat format: "PID:x alive <timestamp>"
        // → timestamp จะอยู่หลัง space ตัวสุดท้าย
        int idx = msg.lastIndexOf(' ');

        if (idx > 0) {
            try {
                // substring หลัง space → แปลงเป็น long
                return Long.parseLong(msg.substring(idx + 1));
            } catch (Exception ignore) {
                // ถ้า parse ไม่ได้ → return 0
            }
        }
        return 0L;
    }

}
