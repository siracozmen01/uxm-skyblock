package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A menu gathers what it draws before it reaches the thread that owns the player.
 *
 * <p>A window is opened by a click or a command, and everything it draws has to be in hand before it
 * is built, because building and opening it happen on the thread that owns the player. The booster
 * window was not: it asked the paused flag, then every active booster, then the active boosters and
 * the effective multiplier of each of six categories, and all fourteen of those ran while that
 * thread waited.
 *
 * <p>This is the fourth door. The sibling guards watch a command before its first hop, the inside of
 * a hop, and an event handler; none of them looks at a menu, which is neither.
 */
class NoMenuReadsStorageWhileThePlayerWaitsTest {

    private static final Path MENUS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/menu");

    /** Where work leaves the thread the click or the command arrived on. */
    private static final List<String> OFF_THREAD =
            List.of("async(", "asyncAfter(", "thenAccept(", "thenApply(", "thenRun(", "thenCompose(");

    /**
     * Where the menu builds the work as a value and hands it to the scheduler further down.
     *
     * <p>A menu writes {@code Runnable asyncTask = () -> { ... };} and then chooses between
     * {@code schedulerPort.async(asyncTask)} and running it in place when it has no scheduler. A
     * lexical scan cannot see that the variable is handed over ten lines later.
     */
    private static final List<String> BUILDS_THE_WORK_FIRST = List.of("Runnable asyncTask = ");

    /**
     * Where the menu comes back to the thread that owns the player.
     *
     * <p>These blocks are written inside the asynchronous one, because that is where the answers
     * are, so the span that says "off the thread" covers them too. They are the part that is not,
     * and a read in one of them is a read while the player waits.
     */
    private static final List<String> BACK_ON_THE_THREAD = List.of("Runnable show = ", "Runnable notify = ");

    /**
     * Any call onto a port or an application service.
     *
     * <p>The sibling guards look for verbs that read a row, and every one of them has leaked: a
     * question shaped like {@code isSomething} reaches the database as hard as a {@code find}, and
     * the method that gathered a whole window in one read was called {@code overview}. A menu has
     * few collaborators and they are named just below, so here the rule is the receiver rather than
     * the verb, and anything not named is a query.
     */
    private static final Pattern STORAGE_CALL = Pattern.compile("\\b\\w*(?:StoragePort|Port|Service)\\.[a-z]\\w*\\(");

    /**
     * Collaborators that answer from memory, so a call onto one is not a query.
     *
     * <p>The scheduler is a thread, the configuration and the catalogue are read at startup, the
     * form service asks the player's own connection, and the session coordinator answers from the
     * session it already holds.
     */
    private static final Pattern NOT_STORAGE = Pattern.compile("^(?:messages|schedulerPort|scheduler|configuration|"
            + "config|bedrockFormService|forms|sessionCoordinator|presetCatalog|protectionListener)\\.");

    /**
     * Single calls onto one of those receivers that touch nothing but memory.
     *
     * <p>The lines of a mission of several are registered with the catalogue at startup and read back from a map.
     */
    private static final List<String> ANSWERED_FROM_MEMORY =
            List.of("recycleService.cancelResetChallenge(", "missionService.linesOf(");

    @Test
    @DisplayName("There really are menus to check, so a clean result means something")
    void theScanFindsSomething() throws IOException {
        try (Stream<Path> files = Files.list(MENUS)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java")).count())
                    .describedAs("menu classes")
                    .isGreaterThan(3);
        }
    }

    @Test
    @DisplayName("No menu reads storage on the thread that owns the player")
    void noMenuQueriesWhileThePlayerWaits() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int callsRead = 0;

        try (Stream<Path> files = Files.walk(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = blankComments(Files.readString(file, StandardCharsets.UTF_8));

                List<int[]> offThread = new ArrayList<>();
                for (String opener : OFF_THREAD) {
                    offThread.addAll(argumentSpans(source, opener));
                }
                for (String opener : BUILDS_THE_WORK_FIRST) {
                    offThread.addAll(statementSpans(source, opener));
                }

                List<int[]> backOnThread = new ArrayList<>();
                for (String opener : BACK_ON_THE_THREAD) {
                    backOnThread.addAll(statementSpans(source, opener));
                }

                Matcher matcher = STORAGE_CALL.matcher(source);
                while (matcher.find()) {
                    callsRead++;
                    if (NOT_STORAGE.matcher(matcher.group()).find() || ANSWERED_FROM_MEMORY.contains(matcher.group())) {
                        continue;
                    }
                    int at = matcher.start();
                    boolean handedBack = backOnThread.stream().anyMatch(span -> span[0] <= at && at <= span[1]);
                    if (!handedBack && offThread.stream().anyMatch(span -> span[0] <= at && at <= span[1])) {
                        continue;
                    }
                    int line = source.substring(0, at).split("\n", -1).length;
                    offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                }
            }
        }

        assertThat(callsRead).describedAs("the guard really read the calls").isGreaterThan(5);
        assertThat(offences)
                .describedAs("a window is built and opened on the thread that owns the player, so "
                        + "everything it draws has to be in hand before it gets there")
                .isEmpty();
    }

    /** The spans covering each argument list of {@code opener}, outermost parenthesis included. */
    private static List<int[]> argumentSpans(String source, String opener) {
        List<int[]> spans = new ArrayList<>();
        for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
            int open = at + opener.length() - 1;
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        spans.add(new int[] {at, i});
                        break;
                    }
                }
            }
        }
        return spans;
    }

    /** The span of a statement that opens a block and closes it with a brace and a semicolon. */
    private static List<int[]> statementSpans(String source, String opener) {
        List<int[]> spans = new ArrayList<>();
        for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
            int brace = source.indexOf('{', at);
            if (brace < 0) {
                continue;
            }
            int depth = 0;
            for (int i = brace; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        spans.add(new int[] {at, i});
                        break;
                    }
                }
            }
        }
        return spans;
    }

    /** Blanks comments so a sentence naming a call is not read as the call. */
    private static String blankComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        while (at < source.length()) {
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                end = end < 0 ? source.length() : end;
                out.append(" ".repeat(end - at));
                at = end;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at);
                end = end < 0 ? source.length() : end + 2;
                for (int i = at; i < end; i++) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
                at = end;
            } else {
                out.append(source.charAt(at));
                at++;
            }
        }
        return out.toString();
    }
}
