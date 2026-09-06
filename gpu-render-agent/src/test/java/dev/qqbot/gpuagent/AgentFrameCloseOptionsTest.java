package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AgentFrameCloseOptionsTest {
    @Test
    void closeDialogOffersTrayExitAndCancel() {
        assertArrayEquals(new Object[] {"最小化到托盘", "直接退出", "取消"}, AgentFrame.closeOptions());
    }

    @Test
    void restartCommandPreservesJavaJarArguments() {
        assertEquals(java.util.List.of("java.exe", "-jar", "agent.jar"),
                AgentFrame.restartCommand("java.exe", new String[] {"-jar", "agent.jar"}));
        assertEquals(java.util.List.of("Litematic GPU Agent.exe"),
                AgentFrame.restartCommand("Litematic GPU Agent.exe", new String[0]));
    }

    @Test
    void javawFallbackRestoresJarPathAndArguments() {
        assertEquals(java.util.List.of("javaw.exe", "-jar", "C:\\Program Files\\Agent\\agent.jar", "--profile", "test"),
                ProcessRelauncher.fromJavaCommand("C:\\Program Files\\Agent\\agent.jar --profile test", "javaw.exe"));
    }

    @Test
    void repeatedWindowsExecutableArgumentIsRemoved() {
        assertEquals(java.util.List.of("-jar", "agent.jar"),
                ProcessRelauncher.removeRepeatedExecutable("C:\\Program Files\\Java\\bin\\javaw.exe",
                        new String[] {"C:\\Program Files\\Java\\bin\\javaw.exe", "-jar", "agent.jar"}));
    }
}
