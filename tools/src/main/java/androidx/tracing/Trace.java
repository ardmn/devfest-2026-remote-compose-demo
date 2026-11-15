package androidx.tracing;

/**
 * Stands in for androidx.tracing on the JVM. remote-creation-compose is compiled against the Android build of
 * androidx.tracing, where Trace.beginSection/endSection are static; in its JVM build (tracing-desktop) they are
 * instance methods of an object — calling them from the AAR's classes fails with IncompatibleClassChangeError.
 * Tracing does nothing in this command-line tool, so these do nothing either.
 */
public final class Trace {
    private Trace() {}

    public static void beginSection(String label) {}

    public static void endSection() {}

    public static boolean isEnabled() {
        return false;
    }
}
