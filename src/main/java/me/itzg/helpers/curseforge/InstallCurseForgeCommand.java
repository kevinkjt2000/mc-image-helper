package me.itzg.helpers.curseforge;

import static me.itzg.helpers.http.Fetch.fetch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.itzg.helpers.files.ResultsFileWriter;
import me.itzg.helpers.http.PathOrUri;
import me.itzg.helpers.http.PathOrUriConverter;
import me.itzg.helpers.http.SharedFetchArgs;
import me.itzg.helpers.json.ObjectMappers;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

@Command(name = "install-curseforge", subcommands = {
    SchemasCommand.class
})
public class InstallCurseForgeCommand implements Callable<Integer> {
    @Option(names = {"--help","-h"}, usageHelp = true)
    boolean help;

    @Option(names = "--output-directory", defaultValue = ".", paramLabel = "DIR")
    Path outputDirectory;

    @Option(names = "--results-file", description = ResultsFileWriter.OPTION_DESCRIPTION, paramLabel = "FILE")
    Path resultsFile;

    @Option(names = "--modpack-page-url", paramLabel = "URL",
        description = "URL of a modpack page such as "
        + "https://www.curseforge.com/minecraft/modpacks/all-the-mods-8"
        + "or a file page https://www.curseforge.com/minecraft/modpacks/all-the-mods-8/files/4248390")
    String pageUrl;

    @Option(names = "--slug", description = "The short-URL identifier")
    String slug;

    @Option(names = "--file-id")
    Integer fileId;

    @ArgGroup(exclusive = false)
    ExcludeIncludeArgs excludeIncludeArgs = new ExcludeIncludeArgs();

    static class ExcludeIncludeArgs {
        @ArgGroup(exclusive = false)
        Listed listed;

        @Option(names = "--exclude-include-file", paramLabel = "FILE|URI",
            description = "A JSON file that contains global and per modpack exclude/include declarations. "
                + "See README for schema.",
            converter = PathOrUriConverter.class
        )
        PathOrUri exludeIncludeFile;

        static class Listed {
            @Option(names = "--exclude-mods", paramLabel = "PROJECT_ID|SLUG",
                split = "\\s+|,", splitSynopsisLabel = ",| ",
                description = "For mods that need to be excluded from server deployments, such as those that don't label as client"
            )
            Set<String> excludedMods;

            @Option(names = "--force-include-mods", paramLabel = "PROJECT_ID|SLUG",
                split = "\\s+|,", splitSynopsisLabel = ",| ",
                description = "Some mods incorrectly declare client-only support, but still need to be included in a server deploy"
            )
            Set<String> forceIncludeMods;

        }
    }


    @Option(names = "--filename-matcher", paramLabel = "STR",
        description = "Substring to select specific modpack filename")
    String filenameMatcher;

    @Option(names = "--force-synchronize")
    boolean forceSynchronize;

    @Option(names = "--parallel-downloads", defaultValue = "4",
        description = "Default: ${DEFAULT-VALUE}"
    )
    int parallelDownloads;

    @Option(names = "--set-level-from",
        description = "When WORLD_FILE, a world file included the modpack will be unzipped into a folder under 'saves' and referenced as 'LEVEL' in the results file."
            + "\nWhen OVERRIDES and the overrides contains a world save directory (contains level.dat), then that directory will be referenced as 'LEVEL' in the results file."
            + "\nIn either case, existing world data will be preserved and skipped if it already exists."
            + "\nValid values: ${COMPLETION-CANDIDATES}"
    )
    LevelFrom levelFrom;

    @Option(names = "--overrides-skip-existing",
        description = "When enabled, existing files will not be replaced by overrides content from the modpack"
    )
    boolean overridesSkipExisting;

    @ArgGroup(exclusive = false)
    SharedFetchArgs sharedFetchArgs = new SharedFetchArgs();

    private static final Pattern PAGE_URL_PATTERN = Pattern.compile(
        "https://www.curseforge.com/minecraft/modpacks/(?<slug>.+?)(/files(/(?<fileId>\\d+)?)?)?");

    @Override
    public Integer call() throws Exception {
        // https://www.curseforge.com/minecraft/modpacks/all-the-mods-8/files
        // https://www.curseforge.com/minecraft/modpacks/all-the-mods-8/files/4248390

        if (pageUrl != null) {
            final Matcher m = PAGE_URL_PATTERN.matcher(pageUrl);
            if (m.matches()) {
                slug = m.group("slug");
                final String fileIdStr = m.group("fileId");
                if (fileIdStr != null) {
                    fileId = Integer.parseInt(fileIdStr);
                }
            }
            else {
                System.err.println("Unexpected URL structure: "+pageUrl);
                return ExitCode.USAGE;
            }
        }

        if (slug == null) {
            System.err.println("A modpack page URL or slug identifier is required");
            return ExitCode.USAGE;
        }

        final ExcludeIncludesContent excludeIncludes = loadExcludeIncludes();

        final CurseForgeInstaller installer = new CurseForgeInstaller(outputDirectory, resultsFile)
            .setExcludeIncludes(excludeIncludes)
            .setForceSynchronize(forceSynchronize)
            .setParallelism(parallelDownloads)
            .setLevelFrom(levelFrom)
            .setOverridesSkipExisting(overridesSkipExisting)
            .setSharedFetchOptions(sharedFetchArgs.options());

        installer.install(slug, filenameMatcher, fileId);

        return ExitCode.OK;
    }

    private ExcludeIncludesContent loadExcludeIncludes() throws IOException {
        final ExcludeIncludesContent fromFile =
            excludeIncludeArgs.exludeIncludeFile != null ? loadExcludeIncludesFile()
                : null;

        if (excludeIncludeArgs.listed != null) {
            if (fromFile != null) {
                // Merge listed global ones with content from file

                return new ExcludeIncludesContent()
                    .setGlobalExcludes(
                        mergeSets(
                            excludeIncludeArgs.listed.excludedMods,
                            fromFile.getGlobalExcludes()
                        ))
                    .setGlobalForceIncludes(
                        mergeSets(
                            excludeIncludeArgs.listed.forceIncludeMods,
                            fromFile.getGlobalForceIncludes()
                        )
                    )
                    .setModpacks(fromFile.getModpacks());
            }
            else {
                return new ExcludeIncludesContent()
                    .setGlobalExcludes(excludeIncludeArgs.listed.excludedMods)
                    .setGlobalForceIncludes(excludeIncludeArgs.listed.forceIncludeMods);

            }
        }
        else {
            return fromFile;
        }
    }

    private Set<String> mergeSets(Set<String> s1, Set<String> s2) {
        if (s1 == null) {
            return s2;
        }
        if (s2 == null) {
            return s1;
        }
        return Stream.concat(
            s1.stream(),
            s2.stream()
        ).collect(Collectors.toSet());
    }

    private ExcludeIncludesContent loadExcludeIncludesFile() throws IOException {
        if (excludeIncludeArgs.exludeIncludeFile.getPath() != null) {
            return ObjectMappers.defaultMapper()
                .readValue(excludeIncludeArgs.exludeIncludeFile.getPath().toFile(),
                    ExcludeIncludesContent.class
                );
        }
        else {
            return fetch(excludeIncludeArgs.exludeIncludeFile.getUri())
                .toObject(ExcludeIncludesContent.class)
                .acceptContentTypes(null)
                .execute();
        }

    }

}
