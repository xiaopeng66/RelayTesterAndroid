import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Falls back to a TCP loopback pipe when this JDK's Windows UNIX-domain
 * socket implementation can bind but cannot connect.
 */
public final class PipeFallbackAgent {
    private PipeFallbackAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            Module javaBase = Object.class.getModule();
            Module agentModule = PipeFallbackAgent.class.getModule();
            instrumentation.redefineModule(
                javaBase,
                Collections.emptySet(),
                Map.of(),
                Map.of("sun.nio.ch", Set.of(agentModule)),
                Collections.emptySet(),
                Map.of()
            );
            Field disabled = Class.forName("sun.nio.ch.PipeImpl")
                .getDeclaredField("noUnixDomainSockets");
            disabled.setAccessible(true);
            disabled.setBoolean(null, true);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to enable the TCP pipe fallback", exception);
        }
    }
}
