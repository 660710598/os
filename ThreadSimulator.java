public class ThreadSimulator extends Thread {
    private final int threadId;
    private final int processId;

    public ThreadSimulator(int threadId, int processId) {
        this.threadId = threadId;
        this.processId = processId;
    }

    @Override
    public void run() {
        System.out.println("Process " + processId + " - Thread " + threadId + " is running.");
        try {
            Thread.sleep(1000); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("Process " + processId + " - Thread " + threadId + " finished.");
    }
}
