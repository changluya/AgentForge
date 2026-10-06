package cloud.changlu.agentforge.agent.tool.mcp.transport;

import cloud.changlu.agentforge.agent.tool.mcp.support.JsonRpcCodec;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP {@code stdio} transport: launches a child process and exchanges newline-delimited JSON-RPC
 * messages over its {@code stdin}/{@code stdout}, exactly one message per line.
 *
 * <p>Server diagnostics on {@code stderr} are drained on a daemon thread so a chatty server cannot
 * dead-lock the pipe.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class StdioMcpTransport implements McpTransport {

    private static final Logger log = Logger.getLogger(StdioMcpTransport.class.getName());

    private final List<String> command;
    private final Map<String, String> environment;
    private final File workingDirectory;

    private final Object lock = new Object();
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread stderrPump;

    private StdioMcpTransport(Builder builder) {
        this.command = Collections.unmodifiableList(new ArrayList<String>(builder.command));
        this.environment =
                Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.environment));
        this.workingDirectory = builder.workingDirectory;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static StdioMcpTransport of(String... command) {
        return builder().command(command).build();
    }

    public static StdioMcpTransport of(List<String> command) {
        return builder().command(command).build();
    }

    @Override
    public void send(String message) throws IOException {
        ensureStarted();
        synchronized (lock) {
            writeLine(message);
        }
    }

    @Override
    public String request(String message) throws IOException {
        ensureStarted();
        Object id = JsonRpcCodec.idOf(message);
        synchronized (lock) {
            writeLine(message);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> parsed;
                try {
                    parsed = Json.parseObject(line);
                } catch (RuntimeException e) {
                    log.log(Level.FINE, "Ignoring non-JSON MCP stdout line: " + line, e);
                    continue;
                }
                Object messageId = parsed.get("id");
                if (messageId != null
                        && id != null
                        && String.valueOf(messageId).equals(String.valueOf(id))) {
                    return line;
                }
                // Notification or a late reply to another request: ignore for the P0 sync model.
            }
            throw new IOException(
                    "MCP stdio server closed the stream before responding to id " + id);
        }
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) {
            return process != null && process.isAlive();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // best effort
                }
                writer = null;
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                process = null;
            }
            reader = null;
            stderrPump = null;
        }
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                return;
            }
            if (command.isEmpty()) {
                throw new IllegalStateException("No stdio command configured");
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory);
            }
            processBuilder.environment().putAll(environment);
            processBuilder.redirectErrorStream(false);
            process = processBuilder.start();
            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            startStderrPump();
        }
    }

    private void writeLine(String message) throws IOException {
        if (writer == null) {
            throw new IOException("MCP stdio transport is not started");
        }
        String singleLine = message.replace("\n", "").replace("\r", "");
        writer.write(singleLine);
        writer.write('\n');
        writer.flush();
    }

    private void startStderrPump() {
        final Process current = process;
        stderrPump =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                BufferedReader errorReader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        current.getErrorStream(),
                                                        StandardCharsets.UTF_8));
                                try {
                                    String line;
                                    while ((line = errorReader.readLine()) != null) {
                                        log.log(Level.FINE, "[mcp-stdio] " + line);
                                    }
                                } catch (IOException ignored) {
                                    // process ended
                                } finally {
                                    try {
                                        errorReader.close();
                                    } catch (IOException ignored) {
                                        // best effort
                                    }
                                }
                            }
                        },
                        "agentforge-mcp-stdio-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    /** Fluent builder for {@link StdioMcpTransport}. */
    public static final class Builder {

        private final List<String> command = new ArrayList<String>();
        private final Map<String, String> environment = new LinkedHashMap<String, String>();
        private File workingDirectory;

        private Builder() {}

        public Builder command(List<String> command) {
            this.command.clear();
            if (command != null) {
                this.command.addAll(command);
            }
            return this;
        }

        public Builder command(String... command) {
            this.command.clear();
            if (command != null) {
                for (String part : command) {
                    this.command.add(part);
                }
            }
            return this;
        }

        public Builder addCommand(String... parts) {
            if (parts != null) {
                for (String part : parts) {
                    this.command.add(part);
                }
            }
            return this;
        }

        public Builder env(String name, String value) {
            if (name != null && value != null) {
                this.environment.put(name, value);
            }
            return this;
        }

        public Builder workingDirectory(File workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public StdioMcpTransport build() {
            if (command.isEmpty()) {
                throw new IllegalStateException("stdio command must not be empty");
            }
            return new StdioMcpTransport(this);
        }
    }
}
