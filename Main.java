import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    public static void main(String[] args) {
        int numProcesses = 3;
        int threadsPerProcess = 3;
        int numHeartbeats = 5; // number of heartbeat rounds to send

        // Map PIDs to unique TCP ports. Use ports starting from 6000.
        final int BASE_PORT = 6000;
        Map<Integer, Integer> pidToPort = new ConcurrentHashMap<>();
        for (int i = 0; i < numProcesses; i++) {
            int pid = 1 + i;
            pidToPort.put(pid, BASE_PORT + pid);
        }

        // Shared member list to track last heartbeat time for each PID
        ConcurrentHashMap<Integer, Long> memberList = new ConcurrentHashMap<>();

        // Create and start each simulated process
        Thread[] processes = new Thread[numProcesses];
        for (int i = 0; i < numProcesses; i++) {
            int pid = 1 + i;
            processes[i] = new Thread(
                new ProcessSim(pid, threadsPerProcess, numHeartbeats,
                               pidToPort, memberList),
                "Process-" + pid);
            processes[i].start();
        }

        // Wait for all processes to finish
        for (Thread p : processes) {
            try {
                p.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("All processes finished.");
    }
}


class ProcessSim implements Runnable {
    private final int pid;
    private final int numThreads;
    private final int numHeartbeats;
    private final Map<Integer, Integer> pidToPort;
    private final ConcurrentHashMap<Integer, Long> memberList;

    public ProcessSim(int pid, int numThreads, int numHeartbeats,
                      Map<Integer, Integer> pidToPort,
                      ConcurrentHashMap<Integer, Long> memberList) {
        this.pid = pid;
        this.numThreads = numThreads;
        this.numHeartbeats = numHeartbeats;
        this.pidToPort = pidToPort;
        this.memberList = memberList;
    }

    @Override
    public void run() {
        synchronized (System.out) {
            System.out.println("Process with PID=" + pid + " started with " + numThreads + " threads.");
        }

        // Start heartbeat listener on the process's port
        int port = pidToPort.get(pid);
        HeartbeatListener listener = new HeartbeatListener(pid, port, memberList);
        Thread listenerThread = new Thread(listener, "HBListener-" + pid);
        listenerThread.start();

        // Start worker threads
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(new WorkerThread(pid, i + 1), "Proc-" + pid + "-T" + (i + 1));
            workers.add(t);
            t.start();
        }

        // Start heartbeat sender
        HeartbeatSender sender = new HeartbeatSender(pid, numHeartbeats, pidToPort);
        Thread senderThread = new Thread(sender, "HBSender-" + pid);
        senderThread.start();

        // Wait for worker threads to finish
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Wait for the heartbeat sender to finish its rounds
        try {
            senderThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Stop the heartbeat listener and wait for it to exit
        listener.shutdown();
        try {
            listenerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (System.out) {
            System.out.println("Process with PID=" + pid + " finished.");
        }
    }
}


class WorkerThread implements Runnable {
    private final int pid;
    private final int tid;
    private static final Object lock = new Object();
    private static final Random rand = new Random();

    public WorkerThread(int pid, int tid) {
        this.pid = pid;
        this.tid = tid;
    }

    @Override
    public void run() {
        for (int i = 1; i <= 5; i++) { // each thread works for 5 rounds
            synchronized (lock) {
                System.out.println("[PID=" + pid + " | TID=" + tid + "] Round " + i
                        + " working in " + Thread.currentThread().getName());
            }
            try {
                Thread.sleep(rand.nextInt(500) + 200); // simulate work time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

class HeartbeatSender implements Runnable {
    private final int pid;
    private final int numHeartbeats;
    private final Map<Integer, Integer> pidToPort;

    public HeartbeatSender(int pid, int numHeartbeats, Map<Integer, Integer> pidToPort) {
        this.pid = pid;
        this.numHeartbeats = numHeartbeats;
        this.pidToPort = pidToPort;
    }

    @Override
    public void run() {
        List<Integer> otherPids = new ArrayList<>();
        for (Integer otherPid : pidToPort.keySet()) {
            if (otherPid != pid) {
                otherPids.add(otherPid);
            }
        }
        for (int round = 1; round <= numHeartbeats; round++) {
            for (Integer otherPid : otherPids) {
                int port = pidToPort.get(otherPid);
                try (Socket socket = new Socket("localhost", port);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                    String message = "PID:" + pid + " alive";
                    out.println(message);
                } catch (IOException e) {
                    // If connection fails, silently ignore as the other process might not be ready
                }
            }
            try {
                Thread.sleep(1000); // send heartbeat every second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}

class HeartbeatListener implements Runnable {
    private final int pid;
    private final int port;
    private final ConcurrentHashMap<Integer, Long> memberList;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public HeartbeatListener(int pid, int port, ConcurrentHashMap<Integer, Long> memberList) {
        this.pid = pid;
        this.port = port;
        this.memberList = memberList;
        this.running = true;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(500); // short timeout to periodically check the running flag
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                        String line = in.readLine();
                        if (line != null && line.startsWith("PID:")) {
                            // Extract sender PID and record the time
                            String[] parts = line.split("\\s+", 2);
                            String pidPart = parts[0];
                            String[] pidSplit = pidPart.split(":");
                            int senderPid = Integer.parseInt(pidSplit[1]);
                            long now = System.currentTimeMillis();
                            memberList.put(senderPid, now);
                            synchronized (System.out) {
                                System.out.println("[Listener " + pid + "] received heartbeat from PID " + senderPid);
                            }
                        }
                    } finally {
                        client.close();
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout: check running flag and continue
                }
            }
        } catch (IOException e) {
            // If binding fails or accept fails repeatedly, we exit
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Stops the listener by closing the server socket and setting the running flag.
     */
    public void shutdown() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
