package com.uxplima.uxmskyblock.bukkit.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Moves the OneBlock phases written inside {@code modules/oneblock.conf}, as every file before phases had files of
 * their own, into {@code oneblock/phases/<name>.conf}, once, and keeps the file as it was beside it as
 * {@code oneblock.conf.bak}.
 */
public final class OneBlockPhaseFiles {

    /** The phases the plugin ships, a file each. */
    public static final List<String> SHIPPED =
            List.of("plains", "underground", "snow", "ocean", "jungle", "nether", "end");

    private static final Logger LOGGER = Logger.getLogger(OneBlockPhaseFiles.class.getName());

    private OneBlockPhaseFiles() {}

    /**
     * Writes every phase {@code file} holds inside its list into {@code folder} and leaves its name in the list in
     * its place. A phase whose file is there already keeps that file, said in the console. A phase whose name no
     * file can have stays in the list as it was.
     *
     * @return how many phases were moved; none when the list holds only names
     */
    public static int moveIntoFiles(Path file, Path folder) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(folder, "folder");
        HoconConfigurationLoader loader =
                HoconConfigurationLoader.builder().path(file).build();
        CommentedConfigurationNode root = loader.load();
        List<? extends CommentedConfigurationNode> listed = root.node("phases").childrenList();
        // Only a phase that can move starts a move: one left in the list for its name would otherwise move nothing
        // on every start and write the backup over with the file it already became.
        if (listed.stream()
                .noneMatch(phase -> phase.isMap()
                        && OneBlockConfiguration.NAME
                                .matcher(phase.node("name").getString(""))
                                .matches())) {
            return 0;
        }
        List<Object> left = new ArrayList<>();
        int moved = 0;
        for (CommentedConfigurationNode phase : listed) {
            if (!phase.isMap()) {
                left.add(phase.raw() == null ? "" : phase.raw());
                continue;
            }
            String name = phase.node("name").getString("");
            if (!OneBlockConfiguration.NAME.matcher(name).matches()) {
                LOGGER.warning(() -> "The OneBlock phase '" + name + "' in " + file.getFileName()
                        + " has no name a file can have, so it stays written in the list.");
                left.add(phase.raw());
                continue;
            }
            Path target = folder.resolve(name + ".conf");
            if (Files.exists(target)) {
                LOGGER.warning(() -> "The OneBlock phase '" + name + "' is written in " + file.getFileName()
                        + " and oneblock/phases/" + name + ".conf is there already. The file is kept, and the phase"
                        + " as the list wrote it is in " + file.getFileName() + ".bak.");
            } else {
                Files.createDirectories(folder);
                CommentedConfigurationNode written = CommentedConfigurationNode.root();
                written.set(phase.raw());
                written.removeChild("name");
                HoconConfigurationLoader.builder().path(target).build().save(written);
                moved++;
            }
            left.add(name);
        }
        Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        CommentedConfigurationNode phases = root.node("phases");
        phases.raw(null);
        for (Object each : left) {
            phases.appendListNode().set(each);
        }
        loader.save(root);
        int count = moved;
        LOGGER.info(() -> count + " OneBlock phases were moved out of " + file.getFileName()
                + " into oneblock/phases, a file each. The file as it was is beside it as " + file.getFileName()
                + ".bak.");
        return moved;
    }
}
