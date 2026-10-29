package com.anorys.chainrunner;

import com.anorys.chainrunner.cli.Batch;
import com.anorys.chainrunner.cli.SelfUpdate;
import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ConfigException;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.Tls;
import com.anorys.chainrunner.tui.App;
import com.anorys.chainrunner.tui.SystemTerminal;
import com.anorys.chainrunner.tui.theme.Theme;

import com.anorys.chainrunner.update.GitHubReleases;
import com.anorys.chainrunner.update.SelfUpdater;
import com.anorys.chainrunner.update.UpdateCache;
import com.anorys.chainrunner.update.UpdateNotifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * {@code chainrunner}: opens the terminal UI, or with {@code --run} makes the calls without it (scripts,
 * CI); {@code chainrunner self-update} installs the latest release. Arguments are parsed by hand so that
 * the native image needs no reflection.
 */
public final class Main {

    public static final int OK = 0;
    public static final int CALLS_FAILED = 1;
    public static final int USAGE = 2;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        SelfUpdater.cleanUp();
        if (args.length > 0 && args[0].equals("self-update")) {
            return new SelfUpdate(out, version()).run(Arrays.copyOfRange(args, 1, args.length), err);
        }
        Arguments a;
        try {
            a = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("chainrunner: " + e.getMessage());
            err.println("chainrunner --help for help.");
            return USAGE;
        }
        if (a.help) {
            out.print(HELP);
            return OK;
        }
        if (a.version) {
            out.println("chainrunner " + version());
            return OK;
        }
        if (a.checkTls) {
            out.println(Tls.describe());
            return OK;
        }
        if (a.checkTerminal) {
            try {
                out.println(SystemTerminal.check());
                return OK;
            } catch (IOException e) {
                err.println(e.getMessage());
                return USAGE;
            }
        }
        ChainRunnerConfig config;
        try {
            config = ConfigLoader.load(a.config);
            if (a.env != null && config.environment(a.env).isEmpty()) {
                throw new ConfigException("unknown environment '" + a.env + "'; available: " + config.environments().keySet());
            }
            if (a.sequence != null && config.sequence(a.sequence).isEmpty()) {
                throw new ConfigException("unknown sequence '" + a.sequence + "'; available: "
                        + config.sequences().stream().map(ChainRunnerConfig.Sequence::name).toList());
            }
        } catch (ConfigException e) {
            err.println("chainrunner: " + e.getMessage());
            return USAGE;
        }
        if (a.list) {
            return Batch.list(config, out);
        }
        boolean interactive = System.console() != null;
        Optional<UpdateNotifier> notifier = a.noUpdateCheck ? Optional.empty()
                : UpdateNotifier.start(version(), interactive, System.getenv(), UpdateCache.forUser(System.getenv()),
                Clock.systemUTC(), current -> GitHubReleases.fromEnvironment(System.getenv(), current, UpdateNotifier.LOOKUP_TIMEOUT));
        try {
            if (a.batch || a.dryRun || !interactive) {
                return new Batch(config, out, err).run(a.env, a.sequence, a.vars, a.dryRun, a.yes);
            }
            try {
                App app = new App(config, Theme.fromEnvironment(), a.env, a.sequence, a.vars);
                notifier.flatMap(n -> n.notice(v -> v)).ifPresent(text -> app.notice(text.lines().findFirst().orElse(text)));
                app.run();
                return OK;
            } catch (SystemTerminal.TerminalUnavailableException e) {
                err.println("chainrunner: " + e.getMessage() + "; use --run outside an interactive terminal.");
                return USAGE;
            } catch (Exception e) {
                err.println("chainrunner: " + e);
                return USAGE;
            }
        } finally {
            boolean color = interactive && System.getenv("NO_COLOR") == null;
            notifier.ifPresent(n -> n.finish(err, v -> color ? "\033[1;33m" + v + "\033[0m" : v));
        }
    }

    public static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/chainrunner-version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    static final class Arguments {
        Path config = Path.of(ConfigLoader.DEFAULT_FILE);
        String env;
        String sequence;
        final Map<String, Object> vars = new LinkedHashMap<>();
        boolean batch;
        boolean dryRun;
        boolean yes;
        boolean list;
        boolean help;
        boolean version;
        boolean checkTerminal;
        boolean checkTls;
        boolean noUpdateCheck;

        static Arguments parse(String[] args) {
            Arguments a = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String inline = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    inline = arg.substring(eq + 1);
                    arg = arg.substring(0, eq);
                }
                switch (arg) {
                    case "-c", "--config" -> a.config = Path.of(inline != null ? inline : value(args, ++i, arg));
                    case "-e", "--env" -> a.env = inline != null ? inline : value(args, ++i, arg);
                    case "-s", "--sequence" -> a.sequence = inline != null ? inline : value(args, ++i, arg);
                    case "--var" -> {
                        String v = inline != null ? inline : value(args, ++i, arg);
                        int sep = v.indexOf('=');
                        if (sep <= 0) {
                            throw new IllegalArgumentException("--var expects NAME=VALUE, got '" + v + "'");
                        }
                        a.vars.put(v.substring(0, sep).strip(), v.substring(sep + 1));
                    }
                    case "--run" -> a.batch = true;
                    case "--dry-run" -> a.dryRun = true;
                    case "-y", "--yes" -> a.yes = true;
                    case "--list" -> a.list = true;
                    case "-h", "--help" -> a.help = true;
                    case "-V", "--version" -> a.version = true;
                    case "--check-terminal" -> a.checkTerminal = true;
                    case "--check-tls" -> a.checkTls = true;
                    case "--no-update-check" -> a.noUpdateCheck = true;
                    default -> throw new IllegalArgumentException("unknown option '" + args[i] + "'");
                }
            }
            return a;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length || args[i].startsWith("-") && args[i].length() > 1) {
                throw new IllegalArgumentException(option + " expects a value");
            }
            return args[i];
        }
    }

    private static final String HELP = """
            chainrunner - runs chains of API calls, each API with its own OAuth2 token.

            Usage: chainrunner [options]
                   chainrunner self-update [--check] [--to X.Y.Z]

            Without --run, opens the terminal UI.

              -c, --config FILE      YAML configuration (default: chainrunner.yaml)
              -e, --env NAME         environment (default: defaultEnvironment)
              -s, --sequence NAME    sequence of steps (default: the first one)
                  --var NAME=VALUE   sets or replaces a variable {{NAME}}; repeatable.
                                     A list is given with commas between values:
                                     --var ids=ACC1,ACC2,ACC3
                  --run              runs without the UI (scripts, CI)
                  --dry-run          shows the requests without sending them
              -y, --yes              does not ask for confirmation (with --run)
                  --list             lists the environments and the sequences
                  --no-update-check  does not look for a newer release
                  --check-tls        says where the trusted certificates come from
              -V, --version          version
              -h, --help             this help

            Environment variables: the ${NAME} of the YAML are read from the environment, then
            from the .env file next to the configuration. NO_COLOR turns colors off,
            CHAINRUNNER_ASCII=1 replaces the Unicode symbols (old consoles),
            CHAINRUNNER_NO_UPDATE_CHECK=1 turns the daily release check off.

            Exit codes: 0 all OK, 1 a step failed, 2 configuration or usage error.
            """;
}
