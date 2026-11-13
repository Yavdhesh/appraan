package dev.appraan.cli;

/**
 * Parsed command line:
 * {@code appraan <PID> [--seconds N] [--interval S] [--port P] [--no-ui] [--kernel-file PATH]}.
 *
 * <p>{@code --seconds 0} means "run until Ctrl+C" (UI mode). {@code kernelFile}
 * is the optional path of the appraanagent {@code --out} file to poll for
 * kernel snapshots (the push feed needs no option and takes precedence).
 */
public record CliOptions(String pid, int seconds, int intervalSeconds, int port, boolean uiEnabled,
                         String kernelFile, String kernelKeyFile,
                         String httpsKeystore, String httpsKeystorePassword) {

    public static final int DEFAULT_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_SECONDS = 5;
    /** Default loopback port for the embedded web UI. */
    public static final int DEFAULT_PORT = 7676;

    public static final class Usage extends Exception {
        /** True when this is a help request (print to stdout, exit 0), false for errors. */
        public final boolean help;

        public Usage(String message) {
            this(message, false);
        }

        public Usage(String message, boolean help) {
            super(message);
            this.help = help;
        }
    }

    public static String usageText() {
        return """
                Appraan — live JVM observer

                Usage:
                  java --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \\
                       -jar appraan.jar <PID> [--seconds N] [--interval S] [--port P] [--no-ui] [--kernel-file PATH]

                Arguments:
                  <PID>              PID of the target JVM (must permit the Attach API)
                  --seconds N        total observation time (default %d;
                                     0 = run until Ctrl+C — UI mode)
                  --interval S       status print / JFR snapshot interval (default %d)
                  --port P           loopback port for the embedded web UI (default %d;
                                     0 = pick a free port; ignored with --no-ui)
                  --no-ui            disable the embedded web UI (console output only)
                  --kernel-file PATH poll this appraanagent --out file for kernel
                                     snapshots (fallback; the agent's --push feed
                                     to /api/kernel/ingest needs no option and wins)
                  --kernel-key-file PATH
                                     base64 AES-256 key file for the kernel feed:
                                     ingest becomes fail-closed — only sealed
                                     appraanagent envelopes are accepted
                                     (create the key with: appraanagent --gen-key)
                  --https-keystore PATH
                                     PKCS12 keystore: run the whole web UI over
                                     HTTPS on the same port (requires the
                                     password option below)
                  --https-keystore-password PW
                                     keystore password, or "env" to read the
                                     APPRAAN_HTTPS_PASSWORD environment variable
                  -h, --help         show this help
                """.formatted(DEFAULT_SECONDS, DEFAULT_INTERVAL_SECONDS, DEFAULT_PORT);
    }

    public static CliOptions parse(String[] args) throws Usage {
        String pid = null;
        int seconds = DEFAULT_SECONDS;
        int interval = DEFAULT_INTERVAL_SECONDS;
        int port = DEFAULT_PORT;
        boolean uiEnabled = true;
        String kernelFile = null;
        String kernelKeyFile = null;
        String httpsKeystore = null;
        String httpsKeystorePassword = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> throw new Usage(usageText(), true);
                case "--seconds" -> seconds = parseNonNegativeInt(args, ++i, "--seconds");
                case "--interval" -> interval = parsePositiveInt(args, ++i, "--interval");
                case "--port" -> port = parsePort(args, ++i);
                case "--no-ui" -> uiEnabled = false;
                case "--kernel-file" -> kernelFile = parsePathValue(args, ++i, "--kernel-file");
                case "--kernel-key-file" -> kernelKeyFile = parsePathValue(args, ++i, "--kernel-key-file");
                case "--https-keystore" -> httpsKeystore = parsePathValue(args, ++i, "--https-keystore");
                case "--https-keystore-password" -> httpsKeystorePassword =
                        parseKeystorePassword(args, ++i);
                default -> {
                    if (a.startsWith("--")) {
                        throw new Usage("Unknown option: " + a + "\n\n" + usageText());
                    }
                    if (pid != null) {
                        throw new Usage("Unexpected extra argument: " + a + "\n\n" + usageText());
                    }
                    pid = a;
                }
            }
        }
        if (pid == null) {
            throw new Usage("Missing <PID>.\n\n" + usageText());
        }
        if (!pid.chars().allMatch(Character::isDigit)) {
            throw new Usage("PID must be a number, got: " + pid);
        }
        if (seconds > 0 && interval > seconds) {
            throw new Usage("--interval (" + interval + ") must not exceed --seconds (" + seconds + ")");
        }
        if (httpsKeystore != null && httpsKeystorePassword == null) {
            throw new Usage("--https-keystore requires --https-keystore-password <pw|env>.");
        }
        if (httpsKeystore == null && httpsKeystorePassword != null) {
            throw new Usage("--https-keystore-password has no effect without --https-keystore.");
        }
        return new CliOptions(pid, seconds, interval, port, uiEnabled, kernelFile,
                kernelKeyFile, httpsKeystore, httpsKeystorePassword);
    }

    /** The password value, resolving the literal {@code env} via APPRAAN_HTTPS_PASSWORD. */
    private static String parseKeystorePassword(String[] args, int idx) throws Usage {
        String value = parsePathValue(args, idx, "--https-keystore-password");
        if ("env".equals(value)) {
            String fromEnv = System.getenv("APPRAAN_HTTPS_PASSWORD");
            if (fromEnv == null || fromEnv.isEmpty()) {
                throw new Usage("--https-keystore-password env was given but the "
                        + "APPRAAN_HTTPS_PASSWORD environment variable is not set.");
            }
            return fromEnv;
        }
        return value;
    }

    private static String parsePathValue(String[] args, int idx, String option) throws Usage {
        if (idx >= args.length) {
            throw new Usage("Option " + option + " needs a value.\n\n" + usageText());
        }
        return args[idx];
    }

    /** Like {@link #parsePositiveInt} but accepts 0 (run-until-Ctrl+C for --seconds). */
    private static int parseNonNegativeInt(String[] args, int idx, String option) throws Usage {
        if (idx >= args.length) {
            throw new Usage("Option " + option + " needs a value.\n\n" + usageText());
        }
        try {
            int v = Integer.parseInt(args[idx]);
            if (v < 0) {
                throw new NumberFormatException("negative");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new Usage("Option " + option + " needs a non-negative integer, got: " + args[idx]);
        }
    }

    private static int parsePort(String[] args, int idx) throws Usage {
        if (idx >= args.length) {
            throw new Usage("Option --port needs a value.\n\n" + usageText());
        }
        try {
            int v = Integer.parseInt(args[idx]);
            if (v < 0 || v > 65535) {
                throw new NumberFormatException("out of range");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new Usage("Option --port needs an integer in 0..65535, got: " + args[idx]);
        }
    }

    private static int parsePositiveInt(String[] args, int idx, String option) throws Usage {
        if (idx >= args.length) {
            throw new Usage("Option " + option + " needs a value.\n\n" + usageText());
        }
        try {
            int v = Integer.parseInt(args[idx]);
            if (v <= 0) {
                throw new NumberFormatException("not positive");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new Usage("Option " + option + " needs a positive integer, got: " + args[idx]);
        }
    }
}
