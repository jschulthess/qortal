package org.qortal.rngit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Push flow 1 on Core's test chain: a node holding the name owner's key
 * creates a repository, takes pushes into its cache and publishes them; a
 * second gateway with its own cache and no key (another node) materialises
 * the same history from QDN alone and stays read-only.
 */
class RngitQdnPublisherTests {

    private static final String NAME = "gitpub";
    private static final String CREATOR = "11111111111111111111111111111111";
    private static final String STRANGER = "22222222222222222222222222222222";

    private Path tmp;
    private Path work;

    @BeforeAll
    static void openRepository() throws DataException {
        Common.setRepositoryInMemory();
    }

    @AfterAll
    static void closeRepository() throws DataException {
        Common.closeRepository();
    }

    @BeforeEach
    void beforeTest() throws Exception {
        Common.useDefaultSettings();
        tmp = Files.createTempDirectory("rngit-qdn-pub");
        work = tmp.resolve("work");
    }

    private RevCommit commit(Git git, String content) throws Exception {
        Files.writeString(work.resolve("f.txt"), content);
        git.add().addFilepattern("f.txt").call();
        return git.commit().setMessage(content).setAuthor("t", "t@example.invalid").setSign(false).call();
    }

    /** What /git/push does to the cache: apply the client's bundle, under the repository lock. */
    private void push(RngitQdnGateway gateway, List<String> haves) throws Exception {
        Path bundle = tmp.resolve("push-" + System.nanoTime() + ".bundle");
        assertTrue(RngitGit.createBundle(work.resolve(".git"), List.of("refs/heads/master"), haves, bundle));
        synchronized (gateway.lock(NAME, "repo")) {
            RngitGit.Result result = RngitGit.applyBundle(gateway.cachePath(NAME, "repo"), bundle,
                    "refs/heads/master", "refs/heads/master", false);
            assertTrue(result.ok, result.message);
        }
    }

    private static RngitRepositories otherNode(Path cache) {
        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        repositories.setQdnGateway(new RngitQdnGateway(cache));
        return repositories;
    }

    @Test
    void publishesCreatePushesAndRules() throws Exception {
        try (Repository repository = RepositoryManager.getRepository();
             Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice, NAME);

            // The publishing node: alice's key, and c: for the creator on her name
            Path config = Files.createDirectories(tmp.resolve("rngit"));
            Files.writeString(config.resolve(RngitQdnPublisher.KEY_FILE), Base58.encode(alice.getPrivateKey()));
            RngitRepositories node = new RngitRepositories(Map.of(), Map.of(NAME, List.of("c:" + CREATOR)), Set.of());
            RngitQdnGateway gateway = new RngitQdnGateway(tmp.resolve("cache-a"));
            node.setQdnGateway(gateway);
            RngitQdnPublisher publisher = RngitQdnPublisher.load(config, gateway);
            gateway.setPublisher(publisher);
            assertTrue(publisher.ownsName(NAME));

            assertTrue(node.resolveGroupPermission(CREATOR, NAME, Permission.CREATE));
            assertFalse(node.resolveGroupPermission(STRANGER, NAME, Permission.CREATE));

            publisher.create(NAME, "repo", CREATOR);
            BlockUtils.mintBlock(repository);
            assertTrue(node.resolvePermission(CREATOR, NAME, "repo", Permission.WRITE), "creator is admin");
            assertFalse(node.resolvePermission(STRANGER, NAME, "repo", Permission.WRITE));

            // First push, published as bundle 1
            RevCommit first = commit(git, "one");
            push(gateway, List.of());
            publisher.publish(NAME, "repo");
            BlockUtils.mintBlock(repository);

            RngitRepositories other = otherNode(tmp.resolve("cache-b"));
            RngitRepositories.Repository seen = other.getRepository(NAME, "repo");
            assertNotNull(seen);
            assertEquals(first.name(), RngitGit.resolveRef(seen.getPath(), "refs/heads/master"));
            assertFalse(other.resolvePermission(CREATOR, NAME, "repo", Permission.WRITE), "read-only without the key");

            // Second push: a thin bundle on top of what QDN already holds
            RevCommit second = commit(git, "two");
            push(gateway, List.of(first.name()));
            publisher.publish(NAME, "repo");
            BlockUtils.mintBlock(repository);

            RngitQdn.Descriptor descriptor = otherNode(tmp.resolve("cache-c")).getQdnGateway().descriptor(NAME, "repo");
            assertEquals(List.of("repo~b~1", "repo~b~2"), descriptor.bundles);
            assertEquals(Map.of("refs/heads/master", second.name()), descriptor.refs);
            assertEquals(second.name(), RngitGit.resolveRef(otherNode(tmp.resolve("cache-d")).getRepository(NAME, "repo").getPath(),
                    "refs/heads/master"));

            // Nothing changed: no further publish
            publisher.publish(NAME, "repo");
            BlockUtils.mintBlock(repository);
            assertEquals(2, otherNode(tmp.resolve("cache-e")).getQdnGateway().descriptor(NAME, "repo").bundles.size());

            // Rules travel in the descriptor
            publisher.setAllowed(NAME, "repo", "adm:" + CREATOR + "\nw:" + STRANGER);
            BlockUtils.mintBlock(repository);
            assertTrue(node.resolvePermission(STRANGER, NAME, "repo", Permission.WRITE));
            assertEquals("adm:" + CREATOR + "\nw:" + STRANGER,
                    otherNode(tmp.resolve("cache-f")).getQdnGateway().descriptor(NAME, "repo").allowed);
        }
    }

    private static void registerName(Repository repository, PrivateKeyAccount owner, String name) throws DataException {
        RegisterNameTransactionData data = new RegisterNameTransactionData(TestTransaction.generateBase(owner), name, "");
        data.setFee(new RegisterNameTransaction(null, null).getUnitFee(data.getTimestamp()));
        TransactionUtils.signAndMint(repository, data, owner);
    }
}
