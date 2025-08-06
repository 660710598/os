public class Main {
    public static void main(String[] args) {
        int processCount = 3;
        int threadsPerProcess = 3;
        Thread[] processes = new Thread[processCount];

        for (int i = 0; i < processCount; i++) {
            int pid = i + 1; // Unique PID
            ProcessSimulator process = new ProcessSimulator(pid, threadsPerProcess);
            processes[i] = new Thread(process);
            processes[i].start();
        }

        for (Thread process : processes) {
            try {
                process.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("All processes finished.");
    }
}
