import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障 02:静态 Map 无界缓存导致堆内存泄漏(老年代持续增长、Full GC 频繁)。
 * 运行: java -Xmx128m HeapLeak.java
 * 根因真值见 expected.md。
 */
public class HeapLeak {
    private static final Map<Long, OrderSnapshot> ORDER_CACHE = new ConcurrentHashMap<>();

    static class OrderSnapshot {
        final long orderId;
        final byte[] payload;

        OrderSnapshot(long orderId, int sizeBytes) {
            this.orderId = orderId;
            this.payload = new byte[sizeBytes];
            // 写入少量字节,确保页被实际占用且不被优化掉
            this.payload[0] = (byte) (orderId & 0xFF);
            this.payload[sizeBytes - 1] = 1;
        }
    }

    public static void main(String[] args) {
        System.out.println("pid=" + ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);

        Thread loader = new Thread(() -> {
            long seq = 0;
            // 启动预热:先塞入 ~32MB,让老年代立即可见升高
            for (int i = 0; i < 128; i++) {
                ORDER_CACHE.put(seq++, new OrderSnapshot(seq, 256 * 1024));
            }
            // 持续泄漏:每 250ms 增加 256KB(~1MB/s);触发 OOM 时不退出进程,继续保压方便诊断
            while (true) {
                try {
                    ORDER_CACHE.put(seq++, new OrderSnapshot(seq, 256 * 1024));
                } catch (OutOfMemoryError oom) {
                    // 故意吞掉并继续循环,模拟线上反复 Full GC 抖动但进程尚未崩溃的状态
                    sleep(500);
                }
                sleep(250);
            }
        }, "cache-loader");

        loader.start();

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
