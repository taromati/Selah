package me.taromati.almah.core.messenger;

public interface TypingHandle extends AutoCloseable {

    @Override
    void close();

    static TypingHandle noop() {
        return () -> {};
    }
}
