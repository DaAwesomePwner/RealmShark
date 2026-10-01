package tomato.history.index;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class JournalLinesTest {
    @Test public void byteOffsetsPrefixAndPartialTail() throws Exception {
        byte[] first="é\r\nsecond\npartial".getBytes(StandardCharsets.UTF_8);
        byte[] appended="é\r\nsecond\npartial completed\nthird\n".getBytes(StandardCharsets.UTF_8);
        List<Long> offsets=new ArrayList<>(); List<String> lines=new ArrayList<>();
        JournalLines.read(new ByteArrayInputStream(appended),first.length,(offset,text)->{ offsets.add(offset); lines.add(text); });
        assertEquals(List.of(0L,4L),offsets); assertEquals(List.of("é\r","second"),lines);
    }
    @Test public void usesReplacementDecodingLikeSessionStore() throws Exception {
        List<String> lines=new ArrayList<>();
        JournalLines.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,10}),2,(offset,text)->lines.add(text));
        assertEquals(List.of("\ufffd"),lines);
    }
    @Test(expected=EOFException.class) public void truncationIsNotACompletePrefix() throws Exception {
        JournalLines.read(new ByteArrayInputStream(new byte[]{10}),5,(offset,text)->{});
    }
}
