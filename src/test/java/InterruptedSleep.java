import org.junit.Test;

public class InterruptedSleep {
    @Test
    public void testInterruptedWaitForBeforeEntering() {
//        CountDownLatch l = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("1");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
