package Test.analysis.synthetic;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** OS lock: a crashed JVM releases it automatically; the file is not a PID marker. */
final class TRBSVUWorkerLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private TRBSVUWorkerLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static TRBSVUWorkerLock acquire(Path output) throws Exception {
        Files.createDirectories(output);
        FileChannel channel = FileChannel.open(output.resolve("worker.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Another worker owns " + output);
            return new TRBSVUWorkerLock(channel, lock);
        } catch (Exception ex) {
            channel.close();
            if (ex instanceof OverlappingFileLockException)
                throw new IllegalStateException("Another worker owns " + output, ex);
            throw ex;
        }
    }

    @Override public void close() throws Exception {
        try { lock.release(); } finally { channel.close(); }
    }
}
