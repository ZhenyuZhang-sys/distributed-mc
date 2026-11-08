package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mcdonate.common.proto.Frame;
import mcdonate.common.proto.Result;
import org.junit.jupiter.api.Test;

class WorkerClientTest {
    @Test
    void longErrorIsTruncatedSoItEncodes() {
        String huge = "x".repeat(100_000);
        String cut = WorkerClient.truncate(huge);
        assertEquals(4000, cut.length());
        Frame.encode(Result.failure(1, cut)); // 不抛异常
        assertEquals("short", WorkerClient.truncate("short"));
    }
}
