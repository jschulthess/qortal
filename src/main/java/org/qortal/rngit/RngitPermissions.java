package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.qortal.rngit.RngitProtocol.IDENTITY_HASH_HEX_LENGTH;

/**
 * rngit's permission model ({@code server.py} {@code parse_permission},
 * {@code permissions_from_allowed_input}, {@code load_allowed_permissions},
 * {@code resolve_permission} and {@code resolve_group_permission}).
 * <p>
 * A rule is {@code permission:target}. Targets are {@code all}/{@code a}/{@code everyone},
 * {@code none}/{@code n}/{@code nobody}, an identity hash, or an alias of one.
 * Target names are matched case-sensitively, as the reference does.
 */
@Slf4j
public final class RngitPermissions {

    /** The eight permission lists a group, repository or document carries. */
    public enum Permission {
        READ("r", "read"),
        WRITE("w", "write"),
        CREATE("c", "create"),
        STATS("s", "stats"),
        RELEASE("rel", "release"),
        INTERACT("i", "interact"),
        PROPOSE("p", "propose"),
        ADMIN("adm", "admin");

        private final List<String> names;

        Permission(String... names) {
            this.names = List.of(names);
        }
    }

    private static final List<String> READWRITE_NAMES = List.of("rw", "readwrite");

    /** Target markers; identity targets are stored as lowercase hex. */
    public static final String TARGET_NONE = "*none";
    public static final String TARGET_ALL = "*all";

    private static final List<String> TGT_NONE_NAMES = List.of("n", "none", "nobody");
    private static final List<String> TGT_ALL_NAMES = List.of("a", "all", "everyone");

    /** Longest a dynamic {@code .allowed} script may run before its output is discarded. */
    private static final long DYNAMIC_ALLOWED_TIMEOUT_SECONDS = 10;

    private RngitPermissions() {
    }

    /** One permission list per {@link Permission}; each a set of targets in rule order. */
    public static final class PermissionSet {
        private final Map<Permission, Set<String>> lists = new EnumMap<>(Permission.class);
        private final boolean dynamic;

        PermissionSet(boolean dynamic) {
            for (Permission p : Permission.values()) lists.put(p, new LinkedHashSet<>());
            this.dynamic = dynamic;
        }

        public static PermissionSet empty() {
            return new PermissionSet(false);
        }

        public Set<String> get(Permission permission) {
            return Collections.unmodifiableSet(lists.get(permission));
        }

        /** Whether these rules came from an executable {@code .allowed} file. */
        public boolean isDynamic() {
            return dynamic;
        }

        void add(Set<Permission> permissions, String target) {
            for (Permission p : permissions) lists.get(p).add(target);
        }

        void addAll(PermissionSet other) {
            for (Permission p : Permission.values()) lists.get(p).addAll(other.lists.get(p));
        }
    }

    /** A parsed rule: the permission lists it adds to, and its target. Null fields mean invalid. */
    static final class Rule {
        final Set<Permission> permissions;
        final String target;

        Rule(Set<Permission> permissions, String target) {
            this.permissions = permissions;
            this.target = target;
        }
    }

    static String resolveAlias(String alias, Map<String, String> aliases) {
        if (TGT_NONE_NAMES.contains(alias.toLowerCase(Locale.ROOT)) || TGT_ALL_NAMES.contains(alias.toLowerCase(Locale.ROOT))) {
            return alias;
        }
        if (isIdentityHashHex(alias)) {
            return alias;
        }
        return aliases.getOrDefault(alias, alias);
    }

    static boolean isIdentityHashHex(String s) {
        return s.length() == IDENTITY_HASH_HEX_LENGTH && s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /** {@code parse_permission}: a {@code permission:target} string. */
    static Rule parseRule(String rule, Map<String, String> aliases) {
        String[] comps = rule.split(":", -1);
        if (comps.length != 2) {
            return new Rule(null, null);
        }

        String perm = comps[0].toLowerCase(Locale.ROOT);
        String target = resolveAlias(comps[1], aliases);

        Set<Permission> permissions = null;
        if (READWRITE_NAMES.contains(perm)) {
            permissions = EnumSet.of(Permission.READ, Permission.WRITE);
        } else {
            for (Permission p : Permission.values()) {
                if (p.names.contains(perm)) {
                    permissions = EnumSet.of(p);
                    break;
                }
            }
        }

        String resolvedTarget;
        if (TGT_NONE_NAMES.contains(target)) {
            resolvedTarget = TARGET_NONE;
        } else if (TGT_ALL_NAMES.contains(target)) {
            resolvedTarget = TARGET_ALL;
        } else if (isIdentityHashHex(target)) {
            resolvedTarget = target.toLowerCase(Locale.ROOT);
        } else {
            if (target.length() == IDENTITY_HASH_HEX_LENGTH) {
                log.error("Invalid identity hash \"{}\" in access permissions", target);
            }
            resolvedTarget = null;
        }

        return new Rule(permissions, resolvedTarget);
    }

    /** Adds a list of rules to a permission set, skipping invalid ones. */
    static void addRules(PermissionSet set, Iterable<String> rules, Map<String, String> aliases) {
        for (String entry : rules) {
            String input = entry.strip();
            if (input.startsWith("#")) continue;
            Rule rule = parseRule(input, aliases);
            if (rule.permissions == null || rule.target == null) continue;
            set.add(rule.permissions, rule.target);
        }
    }

    /** {@code permissions_from_allowed_input}: the text of an {@code .allowed} file, one rule per line. */
    public static PermissionSet fromAllowedInput(String input, Map<String, String> aliases, boolean dynamic) {
        PermissionSet set = new PermissionSet(dynamic);
        if (input != null) {
            addRules(set, input.lines()::iterator, aliases);
        }
        return set;
    }

    /**
     * {@code load_allowed_permissions}: reads an {@code .allowed} file, or runs it
     * and parses its stdout when it is executable. A missing file is no rules.
     */
    public static PermissionSet loadAllowed(Path allowedPath, Map<String, String> aliases) throws IOException {
        if (!Files.isRegularFile(allowedPath)) {
            return fromAllowedInput("", aliases, false);
        }

        if (Files.isExecutable(allowedPath)) {
            Process process = new ProcessBuilder(allowedPath.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out = process.getInputStream().readAllBytes();
            try {
                if (!process.waitFor(DYNAMIC_ALLOWED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Dynamic permissions " + allowedPath + " did not finish in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted running dynamic permissions " + allowedPath, e);
            }
            return fromAllowedInput(new String(out, StandardCharsets.UTF_8), aliases, true);
        }

        return fromAllowedInput(Files.readString(allowedPath, StandardCharsets.UTF_8), aliases, false);
    }

    /**
     * {@code resolve_permission} for an existing repository: repository rules
     * first; group rules only when the repository has none for that permission;
     * admins of either level always pass, except that an explicit {@code none}
     * at the deciding level denies.
     */
    static boolean resolve(String remoteHashHex, PermissionSet repository, PermissionSet group, Permission permission) {
        Set<String> repositoryPermissions = repository.get(permission);
        Set<String> repositoryAdmins = repository.get(Permission.ADMIN);

        if (repositoryPermissions.contains(TARGET_NONE)) return false;
        if (repositoryPermissions.contains(TARGET_ALL)) return true;
        if (repositoryPermissions.contains(remoteHashHex)) return true;
        if (repositoryAdmins.contains(remoteHashHex)) return true;
        if (!repositoryPermissions.isEmpty()) return false;

        return resolveGroup(remoteHashHex, group, permission);
    }

    /** {@code resolve_group_permission}. */
    static boolean resolveGroup(String remoteHashHex, PermissionSet group, Permission permission) {
        Set<String> groupPermissions = group.get(permission);

        if (groupPermissions.contains(TARGET_NONE)) return false;
        if (groupPermissions.contains(TARGET_ALL)) return true;
        if (groupPermissions.contains(remoteHashHex)) return true;
        return group.get(Permission.ADMIN).contains(remoteHashHex);
    }
}
