import java.lang.management.ManagementFactory;

/**
 * 故障 01:经典交叉加锁死锁。
 * 运行: java Deadlock.java   (JDK 11+ 单文件模式;JDK 8 用 javac 后 java Deadlock)
 * 根因真值见 expected.md。
 */
public class Deadlock {
    private static final Object ACCOUNT_LOCK = new Object();
    private static final Object LEDGER_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        System.out.println("pid=" + ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);

        Thread transfer = new Thread(() -> {
            synchronized (ACCOUNT_LOCK) {
                sleep(200);
                synchronized (LEDGER_LOCK) {
                    System.out.println("transfer done");
                }
            }
        }, "transfer-worker");

        Thread audit = new Thread(() -> {
            synchronized (LEDGER_LOCK) {
                sleep(200);
                synchronized (ACCOUNT_LOCK) {
                    System.out.println("audit done");
                }
            }
        }, "audit-worker");

        transfer.start();
        audit.start();

        // 主线程保持存活,模拟服务仍在"运行"
        while (true) {
            sleep(1000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
