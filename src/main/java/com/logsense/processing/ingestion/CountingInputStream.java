package com.logsense.processing.ingestion;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Tracks bytes read so job progress can be reported without knowing the line count in advance. */
public class CountingInputStream extends FilterInputStream {

    private volatile long count;

    public CountingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        count += skipped;
        return skipped;
    }

    public long getCount() {
        return count;
    }
}
