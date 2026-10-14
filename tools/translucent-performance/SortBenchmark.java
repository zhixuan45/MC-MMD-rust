import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** 比较真实深度键的串行/并行排序，复制成本不计入排序耗时。 */
public class SortBenchmark {
    public static void main(String[] args) throws Exception {
        for (String name : args) {
            var bytes = ByteBuffer.wrap(Files.readAllBytes(Path.of(name))).order(ByteOrder.LITTLE_ENDIAN);
            long[] source = new long[bytes.remaining() / 8];
            bytes.asLongBuffer().get(source);
            long[] expected = source.clone(); Arrays.sort(expected);
            long[][] samples = {new long[100], new long[100]};
            for (int round = -40; round < 100; round++) {
                for (int n = 0; n < 2; n++) {
                    int mode = (n + (round & 1)) & 1;
                    long[] values = source.clone();
                    long start = System.nanoTime();
                    if (mode == 0) Arrays.sort(values); else Arrays.parallelSort(values);
                    long elapsed = System.nanoTime() - start;
                    if (!Arrays.equals(values, expected)) throw new AssertionError("排序结果不一致");
                    if (round >= 0) samples[mode][round] = elapsed;
                }
            }
            for (int mode = 0; mode < 2; mode++) {
                Arrays.sort(samples[mode]);
                System.out.printf(Locale.ROOT, "%s n=%d %s median_ms=%.4f p95_ms=%.4f%n",
                    name, source.length, mode == 0 ? "serial" : "parallel",
                    samples[mode][50] / 1e6, samples[mode][95] / 1e6);
            }
        }
    }
}
