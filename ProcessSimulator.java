import java.util.ArrayList;
import java.util.List;

public class ProcessSimulator implements Runnable {
    private final int pid;
    private final int threadCount;
    private final List<ThreadSimulator> threads = new ArrayList<>();

    public ProcessSimulator(int pid, int threadCount) {
        this.pid = pid;
        this.threadCount = threadCount;
    }

    @Override
    public void run() {
        System.out.println("Process " + pid + " started.");
        for (int i = 1; i <= threadCount; i++) {
            ThreadSimulator t = new ThreadSimulator(i, pid);
            threads.add(t);
            t.start();
        }
        for (ThreadSimulator t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("Process " + pid + " finished.");
    }
}
