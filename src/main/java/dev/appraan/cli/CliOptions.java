package dev.appraan.cli;

/** Parsed command line: {@code appraan <PID> [--seconds N] [--interval S]}. */
public record CliOptions(String pid, int seconds, int intervalSeconds) {

    public static final int DEFAULT_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_SECONDS = 5;

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
                       -jar appraan.jar <PID> [--seconds N] [--interval S]

                Arguments:
                  <PID>              PID of the target JVM (must permit the Attach API)
                  --seconds N        total observation time (default %d)
                  --interval S       status print / JFR snapshot interval (default %d)
                  -h, --help         show this help
                """.formatted(DEFAULT_SECONDS, DEFAULT_INTERVAL_SECONDS);
    }

    public static CliOptions parse(String[] args) throws Usage {
        String pid = null;
        int seconds = DEFAULT_SECONDS;
        int interval = DEFAULT_INTERVAL_SECONDS;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> throw new Usage(usageText(), true);
                case "--seconds" -> seconds = parsePositiveInt(args, ++i, "--seconds");
                case "--interval" -> interval = parsePositiveInt(args, ++i, "--interval");
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
        if (interval > seconds) {
            throw new Usage("--interval (" + interval + ") must not exceed --seconds (" + seconds + ")");
        }
        return new CliOptions(pid, seconds, interval);
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
