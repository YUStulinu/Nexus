package nexus.ml.text;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Base64;

/**
 * SentencePiece's "precompiled" normaliser (the nmt_nfkc character map shipped inside a
 * tokenizer.json), ported from the Hugging Face tokenizers implementation.
 *
 * The map is a blob: a 4-byte length, a Darts-clone double-array trie over UTF-8 byte strings, then
 * the null-terminated replacement strings. Text is processed grapheme by grapheme: a grapheme
 * shorter than 6 bytes is looked up whole; otherwise (or if it is not in the map) each of its
 * characters is looked up on its own and kept unchanged when absent. The exact traversal and
 * "first match" rules are reproduced, because they decide how quotes, full-width letters, ligatures
 * or tabs become the characters the vocabulary was trained on.
 */
public final class PrecompiledCharsmap {
    private final int[] units;
    private final byte[] normalized;

    public PrecompiledCharsmap(String base64) {
        byte[] blob = Base64.getDecoder().decode(base64);
        var bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int trieSize = bb.getInt();
        units = new int[trieSize / 4];
        for (int i = 0; i < units.length; i++) units[i] = bb.getInt();
        normalized = new byte[blob.length - 4 - trieSize];
        System.arraycopy(blob, 4 + trieSize, normalized, 0, normalized.length);
    }

    // Darts-clone unit accessors
    private static boolean hasLeaf(int u) {
        return ((u >>> 8) & 1) == 1;
    }

    private static int value(int u) {
        return u & 0x7fffffff;
    }

    private static int label(int u) {
        return u & (0x80000000 | 0xff);
    }

    private static int offset(int u) {
        return (u >>> 10) << ((u & (1 << 9)) >>> 6);
    }

    /** The value of the first (shortest) key that is a prefix of {@code key}, or -1. */
    private int firstPrefixValue(byte[] key) {
        int nodePos = 0;
        int unit = units[nodePos];
        nodePos ^= offset(unit);
        for (byte b : key) {
            int c = b & 0xff;
            if (c == 0) break;
            nodePos ^= c;
            if (nodePos < 0 || nodePos >= units.length) return -1;
            unit = units[nodePos];
            if (label(unit) != c) return -1;
            nodePos ^= offset(unit);
            if (hasLeaf(unit)) return value(units[nodePos]);
        }
        return -1;
    }

    /** The replacement for a chunk, or null. */
    private String transform(String chunk) {
        int index = firstPrefixValue(chunk.getBytes(StandardCharsets.UTF_8));
        if (index < 0) return null;
        int end = index;
        while (end < normalized.length && normalized[end] != 0) end++;
        return new String(normalized, index, end - index, StandardCharsets.UTF_8);
    }

    public String normalize(String text) {
        var sb = new StringBuilder(text.length());
        var it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String grapheme = text.substring(start, end);
            if (grapheme.getBytes(StandardCharsets.UTF_8).length < 6) {
                String norm = transform(grapheme);
                if (norm != null) {
                    sb.append(norm);
                    continue;
                }
            }
            for (int i = 0; i < grapheme.length(); ) {
                int cp = grapheme.codePointAt(i);
                String part = new String(Character.toChars(cp));
                String norm = transform(part);
                sb.append(norm != null ? norm : part);
                i += Character.charCount(cp);
            }
        }
        return sb.toString();
    }
}
