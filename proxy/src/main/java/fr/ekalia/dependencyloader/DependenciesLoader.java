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
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DependenciesLoader {

    private static final Logger LOGGER = LogManager.getLogger(DependenciesLoader.class);

    private static final String DEPENDENCIES_FILE_NAME = "dependencies.json";
    private static final String REPOSITORIES_FILE_NAME = "repositories.json";
    private static final String DEFAULT_REPO_TYPE = "default";
    private static final Gson GSON = new Gson();

    public static URL[] process() {
        File localRepo = new File("/dependencies");
        if (System.getenv("LOCAL_REPOSITORY") != null) {
            localRepo = new File(System.getenv("LOCAL_REPOSITORY"));
        }

        if (!localRepo.exists() || !localRepo.isDirectory()) {
            DependenciesLoader.LOGGER.error("Could not use /dependencies directory and LOCAL_REPOSITORY is not set");
            System.exit(1);
        }

        List<URL> files = DependenciesLoader.loadDependencies(
                new File("dependencies"),
                new File("plugins"),
                localRepo
        );
        
        if (files == null) {
            DependenciesLoader.LOGGER.error("Failed to load dependencies");
            System.exit(1);
        }

        final URL[] newUrls = new URL[files.size()];
        System.arraycopy(files.toArray(new URL[0]), 0, newUrls, 0, files.size());

        return newUrls;
    }
    
    static List<URL> loadDependencies(File dependenciesDirectory, File pluginDirectory, File localRepositoryFolder) {
        // 0. Preconditions
        if (!dependenciesDirectory.exists()) {
            if (!dependenciesDirectory.mkdirs()) {
                DependenciesLoader.LOGGER.error("Could not create directory {}", dependenciesDirectory.getAbsolutePath());
                return null;
            }
        } else if (!dependenciesDirectory.isDirectory()) {
            System.err.printf("%s is not a directory%n", dependenciesDirectory.getAbsolutePath());
            return null;
        } else {
            File repositoriesFile = new File(dependenciesDirectory, DependenciesLoader.REPOSITORIES_FILE_NAME);
            File rootRepositoriesFile = new File(DependenciesLoader.REPOSITORIES_FILE_NAME);

            // Save the repository configuration file, excepted if it already exists on the root directory
            if (repositoriesFile.isFile() && !rootRepositoriesFile.exists()) {
                try {
                    Files.copy(repositoriesFile.toPath(), rootRepositoriesFile.toPath());
                } catch (IOException e) {
                    DependenciesLoader.LOGGER.error("Failed to copy {} to {}", repositoriesFile.getAbsolutePath(), rootRepositoriesFile.getAbsolutePath());
                }
            }

            DependenciesLoader.clear(dependenciesDirectory);

            if (rootRepositoriesFile.isFile()) {
                try {
                    Files.copy(rootRepositoriesFile.toPath(), repositoriesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    DependenciesLoader.LOGGER.error("Failed to copy {} to {}", rootRepositoriesFile.getAbsolutePath(), repositoriesFile.getAbsolutePath());
                }
            }

            File sharedRepositoriesFile = new File(localRepositoryFolder, DependenciesLoader.REPOSITORIES_FILE_NAME);
            if (sharedRepositoriesFile.isFile()) {
                DependenciesLoader.LOGGER.info("Found shared repositories configuration file, using it");
                try {
                    Files.copy(sharedRepositoriesFile.toPath(), repositoriesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    DependenciesLoader.LOGGER.error("Failed to copy {} to {}", sharedRepositoriesFile.getAbsolutePath(), repositoriesFile.getAbsolutePath());
                }
            }
        }

        if (!pluginDirectory.exists() || !pluginDirectory.isDirectory()) {
            DependenciesLoader.LOGGER.error("Could not find plugin directory {}", pluginDirectory.getAbsolutePath());
            return null;
        }

        // 1. Load list from plugins
        File[] pluginFiles = pluginDirectory.listFiles();
        if (pluginFiles == null) {
            DependenciesLoader.LOGGER.error("Could not list files in {}", pluginDirectory.getAbsolutePath());
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
                }
            } catch (Exception e) {
                DependenciesLoader.LOGGER.error("Failed to read dependencies file from {}", pluginFile.getName());
            }
        }

        if (allLoadedConfigurations.isEmpty()) {
            DependenciesLoader.LOGGER.info("Didn't find any dependencies configuration");
            return List.of();
        }

        // 2. Download jars
        RepositoriesConfiguration repositoriesConfiguration = new RepositoriesConfiguration();
        File repositoriesConfigurationFile = new File(dependenciesDirectory, DependenciesLoader.REPOSITORIES_FILE_NAME);
        if (repositoriesConfigurationFile.isFile()) {
            try (FileReader fileReader = new FileReader(repositoriesConfigurationFile)) {
                repositoriesConfiguration = DependenciesLoader.GSON.fromJson(fileReader, RepositoriesConfiguration.class);

                DependenciesLoader.LOGGER.info("Loaded repositories configuration, adding {} repositories", repositoriesConfiguration.getRepositories().size());
            } catch (IOException e) {
                DependenciesLoader.LOGGER.error("Failed to read repositories configuration file");
            }
        }  else {
            DependenciesLoader.LOGGER.info("Didn't find any repositories configuration");
        }

        List<RepositoryConfiguration> configurationRepositories = repositoriesConfiguration.getRepositories();

        // 3. Find dependencies in files
        Set<String> allDependencies = new HashSet<>();
        for (DependencyConfiguration configuration : allLoadedConfigurations) {
            allDependencies.addAll(configuration.getDependencies());
        }

        Set<String> dependenciesWithoutVersion = new HashSet<>();
        Map<String, String> dependencyVersion = new HashMap<>();

        for (String dependency : allDependencies) {
            String[] split = dependency.split(":");
            if (split.length != 3) {
                DependenciesLoader.LOGGER.error("Invalid dependency: {}", dependency);
                continue;
            }

            String dependencyWithoutVersion = split[0] + ":" + split[1];
            if (dependenciesWithoutVersion.contains(dependencyWithoutVersion)) {
                DependenciesLoader.LOGGER.error("Duplicated dependency found: {}", dependencyWithoutVersion);
                continue;
            }

            dependenciesWithoutVersion.add(dependencyWithoutVersion);
            dependencyVersion.put(dependencyWithoutVersion, split[2]);
        }

        // 4. Setup resolver
        RepositorySystemSupplier supplier = new RepositorySystemSupplier();
        RepositorySystem repoSystem = supplier.getRepositorySystem();

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(localRepositoryFolder.toPath());
        session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));

        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
        session.setConfigProperty("aether.connector.basic.threads", 8);

        // Add some logging on download
        session.setRepositoryListener(new AbstractRepositoryListener() {
            private final Map<String, Long> durationCache = new HashMap<>();

            @Override
            public void artifactDownloading(RepositoryEvent event) {
                Artifact artifact = event.getArtifact();
                String fullName = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion() + "." + artifact.getExtension();
                this.durationCache.put(fullName, System.currentTimeMillis());
            }

            @Override
            public void artifactDownloaded(RepositoryEvent event) {
                Artifact artifact = event.getArtifact();
                String fullName = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion() + "." + artifact.getExtension();
                long duration = System.currentTimeMillis() - this.durationCache.getOrDefault(fullName, System.currentTimeMillis());
                DependenciesLoader.LOGGER.info("Downloaded {} of {}:{} from {} in {} ms", artifact.getExtension(), artifact.getArtifactId(), artifact.getBaseVersion(), event.getRepository().getId(), duration);
            }
        });

        List<RemoteRepository> repos = new ArrayList<>();
        repos.add(new RemoteRepository.Builder("central (repo.maven.apache.org)", DependenciesLoader.DEFAULT_REPO_TYPE, "https://repo1.maven.org/maven2/").build());
        repos.add(new RemoteRepository.Builder("oss-snapshots (s01.oss.sonatype.org)", DependenciesLoader.DEFAULT_REPO_TYPE, "https://central.sonatype.com/repository/maven-snapshots/").build());

        for (RepositoryConfiguration repositoryConfiguration : configurationRepositories) {
            String url = repositoryConfiguration.getUrl();
            String id = DependenciesLoader.extractDomain(url);
            Authentication auth = null;

            if (repositoryConfiguration.getUsername() != null && repositoryConfiguration.getPassword() != null) {
                auth = new AuthenticationBuilder()
                        .addUsername(repositoryConfiguration.getUsername())
                        .addPassword(repositoryConfiguration.getPassword())
                        .build();
            }

            repos.add(new RemoteRepository.Builder(id, DependenciesLoader.DEFAULT_REPO_TYPE, url)
                    .setAuthentication(auth)
                    .build());
        }

        // 5. Resolve dependencies
        CollectRequest collect = new CollectRequest();
        for (RemoteRepository repository : repos) {
            collect.addRepository(repository);
        }

        for (String dependency : dependenciesWithoutVersion) {
            String[] split = dependency.split(":");
            String groupId = split[0];
            String artifactId = split[1];

            String groupFolderPath = groupId.replace('.', File.separatorChar);

            File groupFolder = new File(dependenciesDirectory, groupFolderPath);
            if (!groupFolder.exists()) {
                if (!groupFolder.mkdirs()) {
                    DependenciesLoader.LOGGER.error("Could not create directory {}", groupFolder.getAbsolutePath());
                    continue;
                }
            }

            File artifactFolder = new File(groupFolder, artifactId);
            if (!artifactFolder.exists()) {
                if (!artifactFolder.mkdirs()) {
                    DependenciesLoader.LOGGER.error("Could not create directory {}", artifactFolder.getAbsolutePath());
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

                    DependenciesLoader.LOGGER.error("Could not import library {}:{}, as it is already provided by the server with version {}", dependency, neededVersion, foundFiles[0].getName());
                    continue;
                }
            }

            DependenciesLoader.LOGGER.info("Adding dependency {}:{}", dependency, neededVersion);

            collect.addDependency(new Dependency(new DefaultArtifact(dependency + ":" + neededVersion), "compile"));
        }

        // 6. Cleanup dependency folder
        DependenciesLoader.clear(dependenciesDirectory);

        // 7. Resolve dependencies
        DependencyRequest request = new DependencyRequest(collect, null);
        DependencyResult result;

        try {
            result = repoSystem.resolveDependencies(session, request);
        } catch (DependencyResolutionException e) {
            DependenciesLoader.LOGGER.error("Could not resolve dependencies:\n{}", e.getMessage());
            return null;
        }

        System.out.printf("Resolved %d artifacts%n", result.getArtifactResults().size());

        Path targetPath = dependenciesDirectory.toPath();

        // 8. Copy from the local repo to the dependency folder
        return result.getArtifactResults()
                .stream()
                .map(artifactResult -> {
                    Artifact artifact = artifactResult.getArtifact();
                    Path filePath = artifact.getPath();
                    Path targetFilePath = targetPath.resolve(filePath.getFileName());

                    try {
                        Files.copy(filePath, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        DependenciesLoader.LOGGER.error("Failed to copy {}: {}", filePath, e.getMessage());
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
                        DependenciesLoader.LOGGER.error("Failed to load {}: {}", uri, e.getMessage());
                    }

                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static void clear(File file) {
        if (file.isDirectory()) {
            for (File child : Objects.requireNonNull(file.listFiles())) {
                DependenciesLoader.clear(child);

                // Delete the file or the empty directory
                if ((!child.isDirectory() || Objects.requireNonNull(child.listFiles()).length == 0) && !child.delete()) {
                    DependenciesLoader.LOGGER.error("Could not delete {}", child.getAbsolutePath());
                }
            }
        }
    }

    private static String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null) {
                return host.replaceFirst("^www\\.", "");
            }

            return url;
        } catch (URISyntaxException e) {
            return url; // fallback to the raw URL
        }
    }

}
