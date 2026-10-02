package org.qortal.rngit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.rngit.RngitPermissions.PermissionSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The groups and repositories an rngit node serves, with their permissions
 * ({@code server.py} {@code load_repository_group}, {@code load_repository},
 * {@code update_group_permissions}, {@code update_repository_permissions}).
 * <p>
 * A group is a configured directory of bare repositories. Group rules come from
 * {@code <group>.allowed} next to the directory plus the group's {@code [access]}
 * entry in the config; repository rules from {@code <repo>.allowed} next to the
 * repository.
 */
@Slf4j
public final class RngitRepositories {

    @Getter
    public static final class Group {
        private final String name;
        private final Path path;
        private volatile PermissionSet permissions = PermissionSet.empty();
        private final Map<String, Repository> repositories = new ConcurrentHashMap<>();

        Group(String name, Path path) {
            this.name = name;
            this.path = path;
        }
    }

    @Getter
    public static final class Repository {
        private final String name;
        private final String group;
        private final Path path;
        private volatile PermissionSet permissions;
        /** Upstream URL when this repository is a fork, else null ({@code __is_fork}). */
        private final String forkSource;
        /** Upstream URL when this repository is a mirror, else null ({@code __is_mirror}). */
        private final String mirrorSource;

        Repository(String name, String group, Path path, PermissionSet permissions) {
            this.name = name;
            this.group = group;
            this.path = path;
            this.permissions = permissions;

            String type = RngitGit.rngitType(path);
            String source = type == null ? null : RngitGit.upstreamSource(path);
            this.forkSource = "fork".equals(type) ? source : null;
            this.mirrorSource = "mirror".equals(type) ? source : null;
        }
    }

    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, String> aliases;
    private final Map<String, List<String>> accessConfig;
    private final Set<String> blockedIdentities;

    /**
     * @param aliases           identity aliases from {@code [aliases]}, alias to hex hash
     * @param accessConfig      group rules from {@code [access]}, group name to rule list
     * @param blockedIdentities identity hashes (lowercase hex) denied everything
     */
    public RngitRepositories(Map<String, String> aliases, Map<String, List<String>> accessConfig,
                             Set<String> blockedIdentities) {
        this.aliases = Map.copyOf(aliases);
        this.accessConfig = Map.copyOf(accessConfig);
        this.blockedIdentities = Set.copyOf(blockedIdentities);
    }

    public Group getGroup(String name) {
        return name == null ? null : groups.get(name);
    }

    public Repository getRepository(String groupName, String repositoryName) {
        Group group = getGroup(groupName);
        return group == null || repositoryName == null ? null : group.repositories.get(repositoryName);
    }

    public Map<String, Group> getGroups() {
        return Map.copyOf(groups);
    }

    public boolean isBlocked(String remoteHashHex) {
        return blockedIdentities.contains(remoteHashHex);
    }

    /** {@code load_repository_group}. */
    public void loadGroup(String name, Path path) {
        Group existing = groups.get(name);
        if (existing != null && !existing.path.equals(path)) {
            log.error("Repository group path did not match existing entry while loading {}, aborting load", name);
            return;
        }
        Group group = groups.computeIfAbsent(name, n -> new Group(n, path));
        updateGroupPermissions(group);

        int loaded = 0;
        try (Stream<Path> entries = Files.list(path)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (loadRepository(group, entry)) loaded++;
            }
        } catch (IOException e) {
            log.error("Could not list repository group {} at {}", name, path, e);
        }
        log.info("Loaded {} repositor{} for rngit group \"{}\"", loaded, loaded == 1 ? "y" : "ies", name);
    }

    /** {@code update_group_permissions}: the group's {@code .allowed} file, then its {@code [access]} entry. */
    public void updateGroupPermissions(Group group) {
        PermissionSet permissions;
        Path allowedPath = Path.of(group.path + ".allowed");
        try {
            permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not load group permissions from {}", allowedPath, e);
            permissions = PermissionSet.empty();
        }

        List<String> configured = accessConfig.get(group.name);
        if (configured != null) {
            RngitPermissions.addRules(permissions, configured, aliases);
        }
        group.permissions = permissions;
    }

    /** {@code update_repository_permissions}. */
    public void updateRepositoryPermissions(Repository repository) {
        Path allowedPath = Path.of(repository.path + ".allowed");
        try {
            repository.permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not update repository permissions for {}/{} from {}",
                    repository.group, repository.name, allowedPath, e);
        }
    }

    /** {@code load_repository}: registers a bare repository directory in a group. */
    public boolean loadRepository(Group group, Path path) {
        String fileName = path.getFileName().toString();
        if (!Files.isDirectory(path) || fileName.endsWith(".work") || fileName.endsWith(".releases")) {
            return false;
        }
        if (!RngitGit.isGitRepository(path)) {
            log.warn("The directory \"{}\" is not a git repository, skipping", path);
            return false;
        }
        if (!RngitGit.isBareRepository(path)) {
            log.warn("The directory \"{}\" is not a bare git repository, skipping. "
                    + "You can change it to a bare repository using \"git config --bool core.bare true\".", path);
            return false;
        }

        PermissionSet permissions;
        Path allowedPath = Path.of(path + ".allowed");
        try {
            permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not load repository permissions from {}", allowedPath, e);
            permissions = PermissionSet.empty();
        }
        group.repositories.put(fileName, new Repository(fileName, group.name, path, permissions));
        return true;
    }

    /**
     * {@code resolve_doc_permission}: a work document's own rules
     * ({@code <repo>.work/<id>.allowed}, never executed) are checked first and
     * only add to the repository's, except that {@code none} there denies; the
     * repository and group rules then apply as in {@link #resolvePermission}.
     * Stats and release are not document permissions.
     */
    public boolean resolveDocumentPermission(String remoteHashHex, String groupName, String repositoryName, long docId,
                                             Permission permission) {
        Group group = getGroup(groupName);
        Repository repository = getRepository(groupName, repositoryName);
        if (group == null || repository == null) return false;
        if (permission == Permission.STATS || permission == Permission.RELEASE) return false;

        PermissionSet doc = PermissionSet.empty();
        Path allowedPath = Path.of(repository.path + ".work").resolve(docId + ".allowed");
        if (Files.isRegularFile(allowedPath)) {
            try {
                doc = RngitPermissions.fromAllowedInput(Files.readString(allowedPath, java.nio.charset.StandardCharsets.UTF_8), aliases, false);
            } catch (IOException e) {
                log.error("Error while resolving document permission for {}/{}/{}", groupName, repositoryName, docId, e);
            }
        }

        Set<String> docPermissions = doc.get(permission);
        if (docPermissions.contains(RngitPermissions.TARGET_NONE)) return false;
        if (docPermissions.contains(RngitPermissions.TARGET_ALL)) return true;
        if (docPermissions.contains(remoteHashHex)) return true;
        if (doc.get(Permission.ADMIN).contains(remoteHashHex)) return true;

        return RngitPermissions.resolve(remoteHashHex, repository.permissions, group.permissions, permission);
    }

    /**
     * Checks {@code .allowed} content as the reference's set-permissions step
     * does: every non-empty, non-comment line must parse as a rule.
     *
     * @return null if valid, else the reference's error message
     */
    public String validateAllowedContent(String content) {
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            RngitPermissions.Rule rule = RngitPermissions.parseRule(stripped, aliases);
            if (rule.permissions == null || rule.target == null) {
                return "Invalid permission \"" + stripped + "\" on line " + (i + 1);
            }
        }
        return null;
    }

    /** {@code resolve_permission}: false for a blocked identity or an unknown group or repository. */
    public boolean resolvePermission(String remoteHashHex, String groupName, String repositoryName, Permission permission) {
        if (isBlocked(remoteHashHex)) return false;
        Group group = getGroup(groupName);
        Repository repository = getRepository(groupName, repositoryName);
        if (group == null || repository == null) return false;

        return RngitPermissions.resolve(remoteHashHex, repository.permissions, group.permissions, permission);
    }

    /**
     * {@code resolve_group_permission}. Unlike {@link #resolvePermission} the
     * reference does not check the blocklist here; requests from blocked
     * identities are refused before any handler resolves permissions.
     */
    public boolean resolveGroupPermission(String remoteHashHex, String groupName, Permission permission) {
        Group group = getGroup(groupName);
        if (group == null) return false;

        return RngitPermissions.resolveGroup(remoteHashHex, group.permissions, permission);
    }
}
