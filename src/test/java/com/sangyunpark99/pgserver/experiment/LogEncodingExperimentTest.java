package com.sangyunpark99.pgserver.experiment;

import ch.qos.logback.classic.spi.LoggingEvent;
import com.sangyunpark99.pgserver.common.logging.LogEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

@EnabledIfEnvironmentVariable(named = "PG_EXPERIMENT", matches = "log-encoding")
class LogEncodingExperimentTest {

    private static final int LINES = 200_000;
    private static final int ROUNDS = 7;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long sink;

    @Test
    void run() throws Exception {
        LoggingEvent event = LogEvents.approval();
        StructuredLogEncoder boot = LogEvents.encoder(LogEvents.BOOT_LOGSTASH);
        StructuredLogEncoder ours = LogEvents.encoder(LogEvents.OURS);

        for (int i = 0; i < 3; i++) {
            measure(boot, event);
            measure(ours, event);
        }

        double[][] bootRounds = new double[ROUNDS][];
        double[][] oursRounds = new double[ROUNDS][];
        for (int i = 0; i < ROUNDS; i++) {
            bootRounds[i] = measure(boot, event);
            oursRounds[i] = measure(ours, event);
        }

        int bytes = boot.encode(event).length;
        double bootNs = median(bootRounds, 0);
        double oursNs = median(oursRounds, 0);
        double bootAlloc = median(bootRounds, 1);
        double oursAlloc = median(oursRounds, 1);

        String report = String.format("""
                결제 승인 로그 한 줄 (%d바이트), %d줄 x %d라운드 중앙값

                                      CPU ns/줄   할당 B/줄
                Boot logstash         %9.0f   %9.0f
                LogstashJsonFormatter %9.0f   %9.0f
                변화                  %8.1f%%   %8.1f%%
                """, bytes, LINES, ROUNDS, bootNs, bootAlloc, oursNs, oursAlloc,
                100 * (oursNs - bootNs) / bootNs, 100 * (oursAlloc - bootAlloc) / bootAlloc);
        System.out.println(report);
        Path out = Path.of("build/experiment/log-encoding.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    private static double[] measure(StructuredLogEncoder encoder, LoggingEvent event) {
        long cpu = THREADS.getCurrentThreadCpuTime();
        long alloc = THREADS.getCurrentThreadAllocatedBytes();
        long sum = 0;
        for (int i = 0; i < LINES; i++) {
            sum += encoder.encode(event).length;
        }
        sink += sum;
        return new double[] {
                (double) (THREADS.getCurrentThreadCpuTime() - cpu) / LINES,
                (double) (THREADS.getCurrentThreadAllocatedBytes() - alloc) / LINES
        };
    }

    private static double median(double[][] rounds, int index) {
        double[] values = Arrays.stream(rounds).mapToDouble((round) -> round[index]).sorted().toArray();
        return values[values.length / 2];
    }
}
