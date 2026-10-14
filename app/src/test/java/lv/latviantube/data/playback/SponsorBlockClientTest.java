package lv.latviantube.data.playback;

import org.json.JSONArray;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SponsorBlockClientTest {
    @Test public void filtersInvalidRangesAndOtherActionsAndMergesOverlaps() throws Exception {
        JSONArray input = new JSONArray("["
                + "{category:'sponsor',actionType:'skip',segment:[10,20]},"
                + "{category:'sponsor',actionType:'skip',segment:[18,25.1234]},"
                + "{category:'intro',segment:[0,30]},"
                + "{category:'sponsor',actionType:'mute',segment:[1,5]},"
                + "{category:'sponsor',segment:[-1,5]},"
                + "{category:'sponsor',segment:[5,5]},"
                + "{category:'sponsor',segment:[5]},"
                + "{category:'sponsor',segment:['bad',10]},null]");
        List<SponsorBlockClient.Segment> segments = SponsorBlockClient.parse(input);
        assertEquals(1, segments.size());
        assertEquals(10000, segments.get(0).startMs);
        assertEquals(25124, segments.get(0).endMs);
        assertEquals(9999, SponsorBlockClient.skipTo(segments, 9999, 30000));
        assertEquals(25124, SponsorBlockClient.skipTo(segments, 10000, 30000));
        assertEquals(25124, SponsorBlockClient.skipTo(segments, 25124, 30000));
        assertEquals(23000, SponsorBlockClient.skipTo(segments, 18000, 23000));
    }
}
