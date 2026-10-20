package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.uxplima.uxmlib.condition.action.ActionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every place that builds an action context wires every seam the library offers.
 *
 * <p>An unwired seam does not fail. It answers wrongly: a wallet reads every balance as zero, a
 * command sink runs nothing, and the operator's line refuses with no word about why. The seams are
 * read off the library's builder, so a seam the library adds later fails this build instead of
 * waiting to be noticed.
 */
class EverySeamTheEffectEngineOffersIsWiredTest {

    /** The subject, which a context fired for the server may leave out. */
    private static final String SUBJECT = "player";

    /** Who hears the lines. Naming the subject answers this as well. */
    private static final String TARGET = "target";

    @Test
    @DisplayName("Every file that builds a context wires every seam the engine offers")
    void everySeamIsNamedWhereverAContextIsBuilt() throws IOException {
        List<Path> builders = filesThatBuildAContext();

        assertThat(builders)
                .describedAs("the interaction effects build a context, so something here must")
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (Path file : builders) {
            String source = Files.readString(file);
            for (String seam : seams()) {
                if (!source.contains("." + seam + "(")) {
                    missing.add(file.getFileName() + " never names ." + seam + "(...)");
                }
            }
            if (!source.contains("." + SUBJECT + "(") && !source.contains("." + TARGET + "(")) {
                missing.add(file.getFileName() + " names neither a subject nor a target");
            }
        }

        assertThat(missing)
                .describedAs("an unwired seam answers wrongly rather than failing")
                .isEmpty();
    }

    @Test
    @DisplayName("The seams are read off the library, so a new one is a failure rather than a hole")
    void theSeamsComeFromTheLibraryItself() {
        assertThat(seams())
                .describedAs("if this set changed, the library moved and every context here must answer for it")
                .containsExactlyInAnyOrder(
                        "later",
                        "broadcast",
                        "consoleSink",
                        "playerSink",
                        "wallet",
                        "itemStore",
                        "words",
                        "style",
                        "barShown",
                        "perReader");
        assertThat(everySeam()).contains(SUBJECT, TARGET);
    }

    private static Set<String> seams() {
        Set<String> named = new TreeSet<>(everySeam());
        named.remove(SUBJECT);
        named.remove(TARGET);
        return named;
    }

    /**
     * Every public method of the builder that takes something and returns the builder. A seam may take two
     * halves, as the per reader words and paint do, and counting one argument methods only let one through.
     */
    private static Set<String> everySeam() {
        Set<String> found = new LinkedHashSet<>();
        for (Method method : ActionContext.Builder.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == ActionContext.Builder.class
                    && method.getParameterCount() >= 1) {
                found.add(method.getName());
            }
        }
        return found;
    }

    private static List<Path> filesThatBuildAContext() throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("ActionContext.builder(")) {
                    found.add(file);
                }
            }
        }
        return found;
    }
}
