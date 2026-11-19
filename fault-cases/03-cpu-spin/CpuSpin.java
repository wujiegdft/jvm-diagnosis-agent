import java.lang.management.ManagementFactory;

/**
 * 故障 03:业务线程 CPU 死循环飙高(100% 单核打满)。
 * 运行: java CpuSpin.java
 * 根因真值见 expected.md。
 */
public class CpuSpin {
    private static volatile long sink = 0;

    public static void main(String[] args) {
        System.out.println("pid=" + ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);

        Thread spinner = new Thread(CpuSpin::recalculatePrice, "price-calculator");
        Thread heartbeat = new Thread(() -> {
            while (true) {
                sleep(1000);
            }
        }, "heartbeat-worker");
        Thread reporter = new Thread(() -> {
            while (true) {
                sleep(1500);
            }
        }, "metrics-reporter");

        spinner.start();
        heartbeat.start();
        reporter.start();

        while (true) {
            sleep(1000);
        }
    }

    /** 模拟边界条件 bug 导致的无限忙等/计算死循环。 */
    private static void recalculatePrice() {
        long acc = 1L;
        long i = 0L;
        while (true) {
            acc = (acc * 31L + i) ^ (acc >>> 13);
            i++;
            if ((i & 0xFFFFFL) == 0L) {
                sink = acc;
            }
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
