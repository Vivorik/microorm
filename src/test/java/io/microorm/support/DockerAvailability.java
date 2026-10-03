package io.microorm.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects whether a Docker daemon looks reachable, so that integration tests are skipped instead of
 * failing on a machine without Docker.
 *
 * <p>Testcontainers otherwise fails with a connection error that hides the real test results. The check
 * is deliberately cheap: it verifies that the Docker socket exists and is readable and writable by the
 * current user. It cannot tell whether the daemon behind it is still alive - in that case Testcontainers
 * fails loudly, which is the right outcome.
 */
public final class DockerAvailability {

    private static final boolean AVAILABLE = probe();

    private DockerAvailability() {
    }

    /** @return {@code true} when the Docker socket looks usable */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Skips the calling test when Docker is not available.
     *
     * <p>Uses the JUnit assumption mechanism, so the test is reported as skipped and the build stays
     * green.
     */
    public static void assumeDocker() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isAvailable(),
                "Docker is not available, so this integration test is skipped");
    }

    private static boolean probe() {
        String dockerHost = System.getenv("DOCKER_HOST");
        String path = dockerHost != null && dockerHost.startsWith("unix://")
                ? dockerHost.substring("unix://".length())
                : "/var/run/docker.sock";
        try {
            Path socket = Path.of(path);
            return Files.exists(socket) && Files.isReadable(socket) && Files.isWritable(socket);
        } catch (RuntimeException e) {
            return false;
        }
    }
}