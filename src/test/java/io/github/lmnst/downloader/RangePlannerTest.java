package io.github.lmnst.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RangePlannerTest {

    @Test
    void emptyWhenTotalBytesIsZero() {
        assertThat(RangePlanner.plan(0, 1024)).isEmpty();
    }

    @Test
    void singleChunkWhenFileSmallerThanChunkSize() {
        List<ByteRange> ranges = RangePlanner.plan(100, 1024);
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0)).isEqualTo(new ByteRange(0, 100));
    }

    @Test
    void singleChunkWhenFileSizeEqualsChunkSize() {
        List<ByteRange> ranges = RangePlanner.plan(1024, 1024);
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0)).isEqualTo(new ByteRange(0, 1024));
    }

    @Test
    void exactMultipleProducesEqualChunks() {
        List<ByteRange> ranges = RangePlanner.plan(3 * 1024, 1024);
        assertThat(ranges).hasSize(3);
        assertThat(ranges.get(0)).isEqualTo(new ByteRange(0, 1024));
        assertThat(ranges.get(1)).isEqualTo(new ByteRange(1024, 1024));
        assertThat(ranges.get(2)).isEqualTo(new ByteRange(2048, 1024));
    }

    @Test
    void lastChunkGetsTailRemainder() {
        List<ByteRange> ranges = RangePlanner.plan(2500, 1024);
        assertThat(ranges).hasSize(3);
        assertThat(ranges.get(0)).isEqualTo(new ByteRange(0, 1024));
        assertThat(ranges.get(1)).isEqualTo(new ByteRange(1024, 1024));
        assertThat(ranges.get(2)).isEqualTo(new ByteRange(2048, 452));
    }

    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "1023, 1024",
        "1025, 1024",
        "8388608, 8388608",
        "10485760, 1048576"
    })
    void rangesAreContinuousAndCoverTotal(long totalBytes, long chunkSize) {
        List<ByteRange> ranges = RangePlanner.plan(totalBytes, chunkSize);
        assertThat(ranges).isNotEmpty();

        long covered = 0;
        long expectedOffset = 0;
        for (ByteRange r : ranges) {
            assertThat(r.offset()).as("offset continuity").isEqualTo(expectedOffset);
            assertThat(r.length()).as("length > 0").isPositive();
            covered += r.length();
            expectedOffset += r.length();
        }
        assertThat(covered).as("total coverage").isEqualTo(totalBytes);
    }

    @Test
    void httpHeaderValueFormat() {
        ByteRange r = new ByteRange(0, 1024);
        assertThat(r.httpHeaderValue()).isEqualTo("bytes=0-1023");
    }

    @Test
    void httpHeaderValueForLastByte() {
        ByteRange r = new ByteRange(1024, 512);
        assertThat(r.httpHeaderValue()).isEqualTo("bytes=1024-1535");
    }

    @Test
    void throwsOnNegativeTotalBytes() {
        assertThatIllegalArgumentException().isThrownBy(() -> RangePlanner.plan(-1, 1024));
    }

    @Test
    void throwsOnZeroChunkSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> RangePlanner.plan(1024, 0));
    }

    @Test
    void resultIsUnmodifiable() {
        List<ByteRange> ranges = RangePlanner.plan(2048, 1024);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ranges.add(new ByteRange(0, 1)));
    }
}
