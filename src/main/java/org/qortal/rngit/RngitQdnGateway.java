package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.PermissionSet;
import org.qortal.rngit.RngitRepositories.Group;
import org.qortal.rngit.RngitRepositories.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves QDN-backed repositories: every registered Qortal name is a group, and
 * {@code <name>/<repo>} is the repository QDN holds under that name.
 * <p>
 * A repository is materialised into a local bare repository under the cache
 * directory by applying its bundles in order and then setting refs and HEAD
 * exactly as the descriptor states. The cache is a derived view: it is only
 * ever rebuilt from QDN, refreshed when the descriptor's latest transaction
 * changes, and can be deleted at any time.
 * <p>
 * Read-only for now: QDN data is public, so everyone may read, and nothing may
 * be written until publishing is added.
 */
@Slf4j
final class RngitQdnGateway {

    static final long READ_TIMEOUT_MS = 60_000;
    /** Applied bundle identifiers, one per line, kept inside the cached bare repository. */
    static final String APPLIED_FILE = "rngit-qdn-applied";

    private final Path cacheRoot;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, Cached> repositories = new ConcurrentHashMap<>();

    private static final class Cached {
        final byte[] descriptorSignature;
        final Repository repository;

        Cached(byte[] descriptorSignature, Repository repository) {
            this.descriptorSignature = descriptorSignature;
            this.repository = repository;
        }
    }

    RngitQdnGateway(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    static PermissionSet readOnly() {
        return RngitPermissions.fromAllowedInput("r:all", Map.of(), false);
    }

    /** The group for a registered name, or null. */
    Group group(String name) {
        if (name == null || name.isEmpty() || name.contains("/")) return null;
        Group cached = groups.get(name);
        if (cached != null) return cached;
        if (RngitQdn.nameOwner(name) == null) return null;
        return groups.computeIfAbsent(name, n -> RngitRepositories.qdnGroup(n, cacheRoot.resolve(n), readOnly()));
    }

    /** The materialised repository, or null if QDN holds none under that name. */
    Repository repository(String name, String repositoryName) {
        if (group(name) == null || !RngitQdn.isValidRepositoryName(repositoryName)) return null;

        byte[] signature = RngitQdn.latestSignature(name, repositoryName);
        if (signature == null) return null;

        String key = name + "/" + repositoryName;
        Cached cached = repositories.get(key);
        if (cached != null && Arrays.equals(cached.descriptorSignature, signature)) return cached.repository;

        synchronized (key.intern()) {
            cached = repositories.get(key);
            if (cached != null && Arrays.equals(cached.descriptorSignature, signature)) return cached.repository;
            try {
                Repository repository = materialise(name, repositoryName);
                repositories.put(key, new Cached(signature, repository));
                return repository;
            } catch (Exception e) {
                log.warn("Could not materialise QDN repository {}: {}", key, e.getMessage());
                return null;
            }
        }
    }

    private Repository materialise(String name, String repositoryName) throws Exception {
        Path descriptorDir = RngitQdn.readResource(name, repositoryName, READ_TIMEOUT_MS);
        RngitQdn.Descriptor descriptor = RngitQdn.parseDescriptor(descriptorDir);

        Path path = cacheRoot.resolve(name).resolve(repositoryName);
        if (!RngitGit.isGitRepository(path)) {
            Files.createDirectories(path);
            RngitGit.initBare(path);
        }

        Path appliedFile = path.resolve(APPLIED_FILE);
        Set<String> applied = new HashSet<>(Files.exists(appliedFile)
                ? Files.readAllLines(appliedFile, StandardCharsets.UTF_8) : List.of());

        for (String bundleId : descriptor.bundles) {
            if (applied.contains(bundleId)) continue;
            if (!bundleId.startsWith(repositoryName + "~b~")) {
                throw new IOException("Bundle " + bundleId + " does not belong to " + repositoryName);
            }
            Path bundleDir = RngitQdn.readResource(name, bundleId, READ_TIMEOUT_MS);
            RngitGit.Result result = RngitGit.applyBundleAllRefs(path, bundleDir.resolve(RngitQdn.BUNDLE_FILE));
            if (!result.ok) throw new IOException("Could not apply " + bundleId + ": " + result.message);
            Files.writeString(appliedFile, bundleId + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            applied.add(bundleId);
        }

        // The descriptor is authoritative for refs: bundles may carry older tips
        for (String ref : RngitGit.refs(path).keySet()) {
            if (!descriptor.refs.containsKey(ref)) RngitGit.deleteRef(path, ref);
        }
        for (Map.Entry<String, String> ref : descriptor.refs.entrySet()) {
            if (RngitRefs.sanRef(ref.getKey()) == null || RngitRefs.sanSha(ref.getValue()) == null) {
                throw new IOException("Invalid ref in descriptor: " + ref.getKey());
            }
            if (!RngitGit.hasObject(path, ref.getValue())) {
                throw new IOException("Descriptor ref " + ref.getKey() + " points at a missing object");
            }
            RngitGit.Result result = RngitGit.updateRef(path, ref.getKey(), ref.getValue());
            if (!result.ok) throw new IOException("Could not set " + ref.getKey() + ": " + result.message);
        }
        RngitGit.updateHead(path, descriptor.head);

        log.info("Materialised QDN repository {}/{} ({} bundles, {} refs)", name, repositoryName,
                descriptor.bundles.size(), descriptor.refs.size());
        return RngitRepositories.qdnRepository(repositoryName, name, path, readOnly());
    }
}
