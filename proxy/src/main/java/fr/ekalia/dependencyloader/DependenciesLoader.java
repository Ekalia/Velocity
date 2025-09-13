/*
 * Copyright (C) 2025 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package fr.ekalia.dependencyloader;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import dev.jeka.core.api.depmanagement.JkCoordinateDependency;
import dev.jeka.core.api.depmanagement.JkDependency;
import dev.jeka.core.api.depmanagement.JkDependencySet;
import dev.jeka.core.api.depmanagement.JkRepo;
import dev.jeka.core.api.depmanagement.JkRepoSet;
import dev.jeka.core.api.depmanagement.resolution.JkDependencyResolver;
import dev.jeka.core.api.depmanagement.resolution.JkResolutionParameters;
import dev.jeka.core.api.depmanagement.resolution.JkResolveResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DependenciesLoader {

    private static final Logger LOGGER = LogManager.getLogger(DependenciesLoader.class);

    private static final String DEPENDENCIES_FILE_NAME = "dependencies.json";
    private static final String REPOSITORIES_FILE_NAME = "repositories.json";
    private static final Gson GSON = new Gson();

    public static URL[] process() {
        // Hack to set jeka user home
        String jekaUserHome = System.getenv("JEKA_USER_HOME");
        if (jekaUserHome == null || jekaUserHome.isEmpty()) {
            File dependenciesRoot = new File("/dependencies");
            if (dependenciesRoot.isDirectory()) {
                System.setProperty("user.home", dependenciesRoot.getAbsolutePath());
            }
        }

        List<URL> files = DependenciesLoader.loadDependencies(
                new File("dependencies"),
                new File("plugins")
        );
        if (files == null) {
            LOGGER.error("Failed to load dependencies");
            System.exit(1);
        }

        final URL[] newUrls = new URL[files.size()];
        System.arraycopy(files.toArray(new URL[0]), 0, newUrls, 0, files.size());

        return newUrls;
    }

    static List<URL> loadDependencies(File dependenciesDirectory, File pluginDirectory) {
        // 0. Preconditions
        if (!dependenciesDirectory.exists()) {
            if (!dependenciesDirectory.mkdirs()) {
                LOGGER.error("Could not create directory {}", dependenciesDirectory.getAbsolutePath());
                return null;
            }
        } else if (!dependenciesDirectory.isDirectory()) {
            LOGGER.error(String.format("%s is not a directory", dependenciesDirectory.getAbsolutePath()));
            return null;
        } else {
            File repositoriesFile = new File(dependenciesDirectory, DependenciesLoader.REPOSITORIES_FILE_NAME);
            File rootRepositoriesFile = new File(DependenciesLoader.REPOSITORIES_FILE_NAME);

            // Save the repository configuration file, excepted if it already exists on the root directory
            if (repositoriesFile.isFile() && !rootRepositoriesFile.exists()) {
                try {
                    Files.copy(repositoriesFile.toPath(), rootRepositoriesFile.toPath());
                } catch (IOException e) {
                    LOGGER.error("Failed to copy {} to {}", repositoriesFile.getAbsolutePath(), rootRepositoriesFile.getAbsolutePath());
                }
            }

            // Clear the old dependencies in case of a new version
            delete(dependenciesDirectory);

            if (!dependenciesDirectory.mkdir()) {
                LOGGER.error("Could not recreate directory {}", dependenciesDirectory.getAbsolutePath());
                return null;
            }

            if (rootRepositoriesFile.isFile()) {
                try {
                    Files.copy(rootRepositoriesFile.toPath(), repositoriesFile.toPath());
                } catch (IOException e) {
                    LOGGER.error("Failed to copy {} to {}", rootRepositoriesFile.getAbsolutePath(), repositoriesFile.getAbsolutePath());
                }
            }
        }

        if (!pluginDirectory.exists() || !pluginDirectory.isDirectory()) {
            LOGGER.error("Could not find plugin directory {}", pluginDirectory.getAbsolutePath());
            return null;
        }

        // 1. Load list from plugins
        File[] pluginFiles = pluginDirectory.listFiles();
        if (pluginFiles == null) {
            LOGGER.error("Could not list files in {}", pluginDirectory.getAbsolutePath());
            return null;
        }

        List<DependencyConfiguration> allLoadedConfigurations = new ArrayList<>();

        for (File pluginFile : pluginFiles) {
            if (!pluginFile.isFile() || !pluginFile.getName().endsWith(".jar")) {
                continue;
            }

            try (ZipFile zipFile = new ZipFile(pluginFile)) {
                ZipEntry dependenciesJsonEntry = zipFile.getEntry(DependenciesLoader.DEPENDENCIES_FILE_NAME);
                if (dependenciesJsonEntry == null) {
                    continue;
                }

                try (InputStream inputStream = zipFile.getInputStream(dependenciesJsonEntry)) {
                    DependencyConfiguration dependencyConfiguration = DependenciesLoader.GSON.fromJson(new JsonReader(new InputStreamReader(inputStream)), DependencyConfiguration.class);
                    allLoadedConfigurations.add(dependencyConfiguration);

                    LOGGER.info("Loaded dependencies configuration from {}", pluginFile.getName());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read dependencies file from {}", pluginFile.getName());
            }
        }

        if (allLoadedConfigurations.isEmpty()) {
            LOGGER.info("Didn't find any dependencies configuration");
            return List.of();
        }

        // 2. Download jars
        RepositoriesConfiguration repositoriesConfiguration = new RepositoriesConfiguration();
        File repositoriesConfigurationFile = new File(dependenciesDirectory, DependenciesLoader.REPOSITORIES_FILE_NAME);
        if (repositoriesConfigurationFile.isFile()) {
            try (FileReader fileReader = new FileReader(repositoriesConfigurationFile)) {
                repositoriesConfiguration = DependenciesLoader.GSON.fromJson(fileReader, RepositoriesConfiguration.class);
            } catch (IOException e) {
                LOGGER.error("Failed to read repositories configuration file");
            }
        }

        List<RepositoryConfiguration> configurationRepositories = repositoriesConfiguration.getRepositories();

        List<JkRepo> repoSet = new ArrayList<>(3 + configurationRepositories.size());
        repoSet.addAll(List.of(
                JkRepo.ofLocal(),
                JkRepo.ofMavenCentral(),
                JkRepo.ofMavenOssrhPublicDownload()
        ));

        for (RepositoryConfiguration repositoryConfiguration : configurationRepositories) {
            String url = repositoryConfiguration.getUrl();
            String username = repositoryConfiguration.getUsername();
            String password = repositoryConfiguration.getPassword();

            JkRepo repository = JkRepo.of(url);

            LOGGER.info("Added repository {}", url);

            repoSet.add(repository);

            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                continue;
            }

            repository.setCredentials(username, password);
        }

        JkDependencyResolver resolver = JkDependencyResolver.of(JkRepoSet.of(repoSet));
        resolver.parameters.setConflictResolver(JkResolutionParameters.JkConflictResolver.LATEST_VERSION);

        Set<String> allDependencies = new HashSet<>();
        for (DependencyConfiguration configuration : allLoadedConfigurations) {
            allDependencies.addAll(configuration.getDependencies());
        }

        Set<String> dependenciesWithoutVersion = new HashSet<>();
        Map<String, String> dependencyVersion = new HashMap<>();

        for (String dependency : allDependencies) {
            String[] split = dependency.split(":");
            if (split.length != 3) {
                LOGGER.error("Invalid dependency: {}", dependency);
                continue;
            }

            String dependencyWithoutVersion = split[0] + ":" + split[1];
            if (dependenciesWithoutVersion.contains(dependencyWithoutVersion)) {
                LOGGER.error("Duplicated dependency found: {}", dependencyWithoutVersion);
                continue;
            }

            dependenciesWithoutVersion.add(dependencyWithoutVersion);
            dependencyVersion.put(dependencyWithoutVersion, split[2]);
        }

        List<JkDependency> dependenciesToResolve = new ArrayList<>();

        for (String dependency : dependenciesWithoutVersion) {
            String[] split = dependency.split(":");
            String groupId = split[0];
            String artifactId = split[1];

            String groupFolderPath = groupId.replace('.', File.separatorChar);

            File groupFolder = new File(dependenciesDirectory, groupFolderPath);
            if (!groupFolder.exists()) {
                if (!groupFolder.mkdirs()) {
                    LOGGER.error("Could not create directory {}", groupFolder.getAbsolutePath());
                    continue;
                }
            }

            File artifactFolder = new File(groupFolder, artifactId);
            if (!artifactFolder.exists()) {
                if (!artifactFolder.mkdirs()) {
                    LOGGER.error("Could not create directory {}", artifactFolder.getAbsolutePath());
                    continue;
                }
            }

            String neededVersion = dependencyVersion.get(dependency);

            File librariesFolder = new File("libraries", groupFolderPath + File.separator + artifactId);
            if (librariesFolder.isDirectory()) {
                File[] foundFiles = librariesFolder.listFiles();
                if (foundFiles != null && foundFiles.length > 0) {
                    String version = foundFiles[0].getName();
                    if (version.equals(neededVersion)) {
                        continue;
                    }

                    LOGGER.error("Could not import library {}:{}, as it is already provided by the server with version {}", dependency, neededVersion, foundFiles[0].getName());
                    continue;
                }
            }

            dependenciesToResolve.add(JkCoordinateDependency.of(dependency, neededVersion));
        }

        LOGGER.info("Resolving {} dependencies...", dependenciesToResolve.size());

        JkDependencySet dependencySet = JkDependencySet.of(dependenciesToResolve);
        JkResolveResult result = resolver.resolve(dependencySet);
        JkResolveResult.JkErrorReport errorReport = result.getErrorReport();
        if (errorReport.hasErrors()) {
            LOGGER.error("Could not resolve dependencies:\n{}", errorReport);
            return null;
        }

        Path targetPath = dependenciesDirectory.toPath();

        return result.getFiles()
                .getEntries()
                .stream()
                .map(path -> {
                    Path targetFilePath = targetPath.resolve(path.getFileName());

                    try {
                        Files.copy(path, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOGGER.error("Failed to copy {}: {}", path, e.getMessage());
                        return null;
                    }

                    return targetFilePath;
                })
                .filter(Objects::nonNull)
                .map(Path::toUri)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (MalformedURLException e) {
                        LOGGER.error("Failed to load {}: {}", uri, e.getMessage());
                    }

                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            for (File child : Objects.requireNonNull(file.listFiles())) {
                delete(child);
            }
        }
        if (!file.delete()) {
            LOGGER.error("Could not delete {}", file.getAbsolutePath());
        }
    }

}
