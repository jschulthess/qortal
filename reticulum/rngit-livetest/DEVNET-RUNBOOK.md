# rngit on devnet: QDN-backed repositories end to end

Proves the QDN-backed rngit on real nodes:

- **Phase 3 exit test (steps 0–7):** a repository pushed through node **A** is
  cloned through node **B** while **A** is offline, with access granted through
  a published account ↔ RNS identity binding.
- **Push flow 2 (steps 8–10):** a push through **B**, which cannot publish for
  the name, is staged; the name's owner publishes it; every node then serves it.
- **Nomad Network pages (step 12):** the same repository browsed as Nomad
  Network pages through **B**, with **A** offline.

Everything here is manual. Each step says what to run and what to check;
stop at the first check that fails and see *Troubleshooting*.

## Roles

| Role | Where | What |
|---|---|---|
| **A** | `devnet-node-1` | rngit node with a `publisher_key`; publishes for the name `devgit` |
| **B** | `devnet-node-2` | rngit node without a key: QDN gateway that stages permitted pushes |
| **C** (optional) | `devnet-node-3` | second read-only gateway, to show B alone serves the data |
| **Publisher account P** | key on A | owns the name `devgit`, pays the fees |
| **Developer account U** | your Hub/wallet | owns the name `devuser`; binds your RNS identity |
| **Laptop** | you | Python RNS, `git`, `rngit`, `git-remote-rns` |

P and U can be the same account if you only have one with QORT, but then
step 4 no longer tests that a *different* account's binding grants access.

You need: names `devgit` (owned by P) and `devuser` (owned by U) registered
on devnet, and a few QORT on P (each publish is a transaction at the unit
fee, currently 0.01 QORT: create = 1, each push = 2, publishing a staged push
= 2) and on U (binding = 1).

## 0. Build and deploy

```bash
cd ~/git/qortal-claude
./ideploy_jar_devnet.sh
```

**Check:** the jar on the nodes is built from `reticulum-git` at or after
commit `9d7a2c18` (`git log --oneline -1`), which adds the page node (step 12).
Steps 0–11 need only `7c47c1ff` (staging).

## 1. Enable rngit on A and B

On **A** and **B** (and **C**), add to `~/qortal/settings.json`:

```json
"rngitEnabled": true
```

(`rngitConfigPath` defaults to `rngit`, i.e. `~/qortal/rngit`.) Restart Core.

**Check** in `~/qortal/log.txt` on each node:

```
rngit QDN gateway enabled: every registered name is a repository group
Reticulum Git Node "Anonymous Git Node" listening on <DEST>
```

Note each node's `<DEST>`; below they are `DEST_A`, `DEST_B`, `DEST_C`.
The first start also wrote `~/qortal/rngit/config`, `repositories_identity`
and `client_identity`.

## 2. Laptop: connect to the devnet mesh

Find a devnet node that runs a gateway (backbone server) interface and read
its `~/qortal/.reticulum/config.yml`. You need, from its
`BackboneServerInterface` section: `listen_port`, `network_name` and, if set,
`passphrase`. Below: `GW_HOST`, `GW_PORT`, `NET_NAME`, `PASSPHRASE`.

Create `~/rngit-devnet/rns/config` on the laptop:

```ini
[reticulum]
  enable_transport = False
  share_instance = No

[logging]
  loglevel = 4

[interfaces]
  [[devnet gateway]]
    type = TCPClientInterface
    interface_enabled = True
    target_host = GW_HOST
    target_port = GW_PORT
    network_name = NET_NAME
    passphrase = PASSPHRASE   # omit the line if the gateway has none
```

```bash
export RNS_CONFIG=~/rngit-devnet/rns
export RNGIT_CONFIG=~/rngit-devnet/client
mkdir -p $RNGIT_CONFIG
rnstatus --config $RNS_CONFIG     # the interface should show as Up
rnpath --config $RNS_CONFIG DEST_A
```

**Check:** `rnpath` finds a path to `DEST_A` (and to `DEST_B`). If it times
out, the interface access code (network name/passphrase) or the port is
wrong; see *Troubleshooting*.

Your rngit client identity is created on first use; to know its hash now:

```bash
python3 -c "import RNS,os; p=os.path.expanduser('$RNGIT_CONFIG/client_identity'); \
i=RNS.Identity.from_file(p) if os.path.exists(p) else RNS.Identity(); i.to_file(p); print(i.hash.hex())"
```

Below this hash is `MY_RNS`.

## 3. Publish your binding (developer account U)

On the laptop, bind your RNS identity to U:

```bash
cd ~/git/qortal-claude/tools/rngit
./rns-identity-binding.py create --address <U's address> -i $RNGIT_CONFIG/client_identity > ~/rngit-devnet/binding.json
./rns-identity-binding.py verify --address <U's address> ~/rngit-devnet/binding.json
```

**Check:** `verify` prints `valid <MY_RNS>`.

Publish it through any node's API, e.g. A through an SSH tunnel:

```bash
ssh -N -L 12391:localhost:12391 devnet-node-1 &
ssh devnet-node-1 cat qortal/apikey.txt > ~/rngit-devnet/apikey.txt
./rns-identity-binding.py publish --name devuser --api-key-file ~/rngit-devnet/apikey.txt ~/rngit-devnet/binding.json
# prompts for U's Base58 private key
```

**Check:** prints `Submitted: true` (or the transaction). After the next
block:

```bash
curl -s "http://localhost:12391/arbitrary/resource/status/JSON/devuser/rns-identity"
```

shows the resource as known (e.g. `PUBLISHED`/`READY`).

## 4. Make A the publisher for `devgit`

On **A**:

```bash
echo '<P Base58 private key>' > ~/qortal/rngit/publisher_key
chmod 600 ~/qortal/rngit/publisher_key
```

and in `~/qortal/rngit/config`, under `[access]`, let the owner of `devuser`
create repositories under `devgit`:

```ini
[access]
  devgit = c:name:devuser
```

Restart Core on A.

**Check** in A's `log.txt`:

```
rngit QDN publishing enabled for names owned by <P's address>
```

## 5. Create a repository and push through A

```bash
rngit create --config $RNGIT_CONFIG --rnsconfig $RNS_CONFIG rns://DEST_A/devgit/hello
```

**Check:** `Repository devgit/hello created`. In A's `log.txt`:
`Creating QDN repository devgit/hello` and `Published QDN resource devgit/hello`.

This step exercises the binding: you were allowed to create because
`c:name:devuser` resolved, through U's binding, to `MY_RNS`. If it says
*Not allowed*, see *Troubleshooting*.

Push a repository with a few commits:

```bash
mkdir -p ~/rngit-devnet/hello && cd ~/rngit-devnet/hello
git init -b master && echo hello > README.md && git add . && git commit -m "first"
head -c 2000000 /dev/urandom > blob.bin && git add . && git commit -m "2 MB blob"
git remote add origin rns://DEST_A/devgit/hello
git push origin master
```

**Check:** the push succeeds. Within a few seconds A's `log.txt` shows
`Published devgit/hello to QDN: 1 refs, new bundle hello~b~1`. After the
next block:

```bash
curl -s "http://localhost:12391/arbitrary/resources/search?service=GIT_REPOSITORY&name=devgit"
```

lists `hello` (the descriptor) and `hello~b~1`.

## 6. Clone through B while A is online

```bash
cd ~/rngit-devnet && rm -rf clone-b1
git clone rns://DEST_B/devgit/hello clone-b1
```

**Check:** the clone matches (`git -C clone-b1 log --oneline` shows both
commits; `cmp clone-b1/blob.bin hello/blob.bin`). B's `log.txt` shows
`Materialised QDN repository devgit/hello (1 bundles, 1 refs)`.

B now holds the data in its own QDN storage. Confirm on B:

```bash
curl -s "http://localhost:12391/arbitrary/resource/status/GIT_REPOSITORY/devgit/hello~b~1"
```

shows it as downloaded/ready on B.

## 7. Exit test: A offline

Stop Core on **A** (`./stop.sh`). On **B**, delete the rngit cache so the
next clone rebuilds from QDN, not from the cache:

```bash
rm -rf ~/qortal/rngit/qdn-cache
```

Then:

```bash
cd ~/rngit-devnet && rm -rf clone-b2
git clone rns://DEST_B/devgit/hello clone-b2
```

**Check (exit criterion):** the clone succeeds with A offline and matches
`hello/`. B's `log.txt` shows a new `Materialised QDN repository devgit/hello`.

Optional, stronger: clone through **C**, which has never seen the repository,
so it must fetch the QDN data from B:

```bash
git clone rns://DEST_C/devgit/hello clone-c
```

This depends on B serving that data to C (B's storage policy) and can take a
minute while C fetches; the gateway waits up to 60 s per resource.

## 8. Push flow 2: a push through B is staged

B cannot publish for `devgit`, but the repository's rules (in its descriptor:
`adm:MY_RNS` from step 5) let you write, so B stages the push. With A still
offline:

```bash
cd ~/rngit-devnet/clone-b2 && echo more >> README.md && git commit -am "staged change"
git push origin master        # origin is DEST_B
```

**Check:** git reports the ref as rejected with
`Staged as #1, awaiting the owner's approval` and exits non-zero; this is
intended, as the repository has not changed yet. B's `log.txt` shows
`Staged change #1 to devgit/hello by MY_RNS: bundle refs/heads/master`.

List it through B's API (a second tunnel, to B):

```bash
ssh -N -L 12491:localhost:12391 devnet-node-2 &
curl -s http://localhost:12491/git/devgit/hello/staged
```

**Check:** one entry, `"id":1`, `"kind":"bundle"`, `"applies":true`, `"base"`
= the current `master`, `"sha"` = your new commit (`git -C ~/rngit-devnet/clone-b2 rev-parse HEAD`).

A push by an identity the rules do not allow is refused outright, not
staged. Optional check with a second, unbound identity:

```bash
RNGIT_CONFIG=~/rngit-devnet/stranger git push origin master   # creates a new client identity
```

**Check:** `Not allowed` (or `refused list`), and the staged list is unchanged.

## 9. The owner publishes the staged push

Fetch the prepared resources from B:

```bash
curl -s http://localhost:12491/git/devgit/hello/staged/1/publish > ~/rngit-devnet/staged-1.json
python3 -c "import json; d=json.load(open('$HOME/rngit-devnet/staged-1.json')); print([r['identifier'] for r in d['resources']])"
```

**Check:** prints `['hello~b~2', 'hello']`: a bundle with your commit's
objects, then the updated descriptor. (`hello~b~N` with N = 1 + the bundles
already published; no bundle at all if the change brought no new objects.)

**With Hub (the intended path):** in a Q-App running as **P**, pass the
resources unchanged:

```js
const prepared = await (await fetch("/git/devgit/hello/staged/1/publish")).json();
await qortalRequest({ action: "PUBLISH_MULTIPLE_QDN_RESOURCES", resources: prepared.resources });
```

**Without a Q-App (for this test):** build, sign and process each resource
through B's API with P's key. B stores the data and keeps serving it while A
is offline:

```bash
ssh devnet-node-2 cat qortal/apikey.txt > ~/rngit-devnet/apikey-b.txt
python3 - ~/rngit-devnet/staged-1.json <<'PYEOF'
import getpass, json, os, sys, urllib.parse, urllib.request
api = "http://localhost:12491"
key = open(os.path.expanduser("~/rngit-devnet/apikey-b.txt")).read().strip()
prepared = json.load(open(sys.argv[1]))
private_key = getpass.getpass("Base58 private key of P (owner of devgit): ").strip()

def call(path, body, content_type="text/plain"):
    req = urllib.request.Request(api + path, data=body.encode(), method="POST",
                                 headers={"Content-Type": content_type, "X-API-KEY": key})
    with urllib.request.urlopen(req, timeout=300) as response:
        return response.read().decode().strip()

for r in prepared["resources"]:   # bundle first, then the descriptor
    query = urllib.parse.urlencode({"filename": r["filename"], "title": r["title"], "fee": 1000000})
    path = "/arbitrary/%s/%s/%s/base64?%s" % (r["service"], urllib.parse.quote(r["name"], safe=""),
                                             urllib.parse.quote(r["identifier"], safe=""), query)
    unsigned = call(path, r["data64"])
    signed = call("/transactions/sign", json.dumps({"privateKey": private_key, "transactionBytes": unsigned}),
                  "application/json")
    print(r["identifier"], "->", call("/transactions/process", signed))
PYEOF
```

**Check:** both lines end in `-> true`.

## 10. Every node serves the published change

After the next block:

```bash
curl -s http://localhost:12491/git/devgit/hello/staged      # []
cd ~/rngit-devnet && rm -rf clone-b3 && git clone rns://DEST_B/devgit/hello clone-b3
git -C clone-b3 log --oneline -1                             # "staged change"
```

**Check:** the staged list is empty (published changes are cleared), and the
new clone has your commit. With **C**: `git clone rns://DEST_C/devgit/hello`
shows it too. Start A again: a clone through A shows it as well (A rebuilds
its cache because the descriptor it published is no longer the latest).

Optional, conflicts: stage two pushes from two clones of the same commit
(each fast-forwards on its own); publish the first as in step 9. **Check:**
`/staged` now shows the second with `"applies":false`, and its `publish`
answers `Staged change #N no longer applies: ...`.

## 11. Optional: compaction

On A set `[qdn] compact_after = 2`, restart, and push three more commits
through A. **Check:** A's log shows
`Compacting devgit/hello: 2 bundles replaced by hello~b~N`; a fresh clone
through B (after deleting B's `qdn-cache`) still matches.

## 12. Nomad Network pages through B

The page node serves the repositories as Nomad Network pages, on the
`nomadnetwork.node` destination of B's rngit identity. It follows the same
permissions as git and reads QDN repositories from B's cache, so it works with
**A** still offline.

On **B**, add to `~/qortal/rngit/config`:

```ini
[pages]
  serve_nomadnet = yes
```

Restart Core on B.

**Check** in B's `log.txt`:

```
Git Nomad Network Node listening on <PAGE_B>
```

`PAGE_B` is the page node's destination; it differs from `DEST_B`. B
announces it about 5 s after start, and then every `announce_interval`
minutes, 360 by default.

**With Nomad Network (the intended path):** on the laptop, run
`nomadnet --rnsconfig $RNS_CONFIG`.

1. Open *[ Network ]* and press Ctrl-L to switch to the announce stream.
2. B shows up under *Nodes* as "Anonymous Git Node", or as its `node_name` if
   one is set. Select it and choose *Connect*.

To open a page by address instead, press Ctrl-U in the browser pane. The
dialog starts with the current address, so clear it with Ctrl-L before typing
`PAGE_B:/page/index.mu`. Downloads are saved to `~/Downloads`, or to
`downloads_path` in Nomad Network's config.

1. **Front page.** Configured groups are listed; there are none on B, so it
   says *No groups available*. Below that is the *Qortal Names* box. Enter
   `devgit` and choose *Open*.
   **Check:** the group page lists `hello`.
2. **Repository page.** Open `hello`.
   **Check:**
   - The page shows `rns://DEST_B/devgit/hello` in the breadcrumb.
   - It shows `Commits (3)`: two from step 5 and the staged change from step 10.
     If you ran step 11, it shows `Commits (6)`.
   - It shows the README, `hello` and `more`.
3. **Files and commits.** Open *Files* and then `README.md`. Open *Commits*
   and then the "staged change" commit.
   **Check:** the commit page shows the diff adding `more`.
4. **Download.** In the blob view of `blob.bin`, choose *Download*.
   **Check:** the download is a 2 MB file, identical to `hello/blob.bin`.

**Without Nomad Network (scripted):** use the client the live test uses. It
links to the page node of the rngit node with the given destination:

```bash
cd ~/git/qortal-claude/tools/rngit-livetest
cat > ~/rngit-devnet/pages.json <<'EOF'
[{"path": "/page/group.mu", "data": {"var_g": "devgit"}},
 {"path": "/page/repo.mu", "data": {"var_g": "devgit", "var_r": "hello"}},
 {"path": "/file/download", "data": {"var_g": "devgit", "var_r": "hello", "var_ref": "HEAD", "var_path": "README.md"}}]
EOF
python3 pages_client.py $RNS_CONFIG DEST_B ~/rngit-devnet/pages.json
```

**Check:** the output is one JSON line per request, after a `page_node` line
that shows `PAGE_B`:
- The group page contains `r=hello`.
- The repository page contains `Commits (3)`, or `Commits (6)` after step 11.
- The download is `"name": "README.md"`, with your README as hex.

The client takes a fourth argument, a client identity file, to identify on the
link before requesting. Only then do pages that need permissions beyond
`r:all` show anything; QDN repositories are public, so they don't.

Optional, requiring identification: add the null identity
`d7db22f63b453c23bb0688dde565b7c1` to `blocked_identities` under `[rngit]` on
B and restart.

**Check:** without identifying, every page says *No Identity*. Nomad Network
connects anonymously until told otherwise, per node:

1. In the browser, press Ctrl-S and choose *Save* to add B to *Known Nodes*.
2. Select B in *Saved Nodes* and press Ctrl-E.
3. Tick *Identify when connecting* and choose *Save*.
4. Disconnect (Ctrl-W) and connect to B again.

**Check:** the pages appear again. Remove the entry from `blocked_identities`
afterwards.

## Cleanup

- `rngitEnabled: false` (or remove it) in `settings.json` disables rngit.
- Remove `[pages]` (or set `serve_nomadnet = no`) to stop serving pages.
- `~/qortal/rngit/qdn-cache` can be deleted at any time.
- Remove `~/qortal/rngit/publisher_key` from A when done; it is a private key.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `rnpath` / `Requesting path...` times out | Laptop interface: wrong `target_port`, or `network_name`/`passphrase` differ from the gateway (interface access codes must match). |
| `rngit QDN gateway disabled` in log | Core's repository was not available when rngit started; check the log above it. |
| No `QDN publishing enabled` line on A | `publisher_key` missing, unreadable, or not a Base58 32-byte key (see the error logged). |
| `create` says *Not allowed* | The binding does not resolve: check step 3's resource exists, was published by **U** (the owner of `devuser`), and lists `MY_RNS`; bindings are cached for 5 minutes, so restart A or wait. As a fallback, `devgit = c:MY_RNS` in `[access]` tests everything else. |
| `create` says *Could not publish repository* | P lacks QORT for the fee, or P does not own `devgit`; the reason is in A's log (`QDN publish ... invalid: ...`). |
| Push succeeds but no `Published ... to QDN` | Publishing runs after the ack; look for `Publishing devgit/hello to QDN failed` in A's log. The next push retries. |
| Push through B says `Not allowed` instead of staging | The descriptor's rules do not grant you `w`/`adm` (check `GET /git/devgit/hello` on any node and A's rules via `rngit perms`), or B's jar predates `7c47c1ff`. |
| `/staged/1/publish` says *no longer applies* | The repository moved on since staging and the change no longer fast-forwards; push again (rebased) through B. |
| Step 9 publish fails `INSUFFICIENT_FEE`/`INVALID_NAME_OWNER` | P lacks QORT, or the key is not the owner of `devgit`. |
| No `Git Nomad Network Node listening` line on B | `[pages]` is missing, or `serve_nomadnet` is misspelled in B's rngit config, or the jar predates `9d7a2c18`. |
| Nomad Network does not list B, or `pages_client.py` waits for a path | The page node announced before the laptop connected. Open `PAGE_B:/page/index.mu` directly; the client requests a path itself. Otherwise restart B, or set a short `announce_interval` under `[rngit]`. |
| Group page says *Group Not Found* for `devgit` | B cannot read the name's repositories from QDN: check that `GET /git/devgit` on B lists `hello`. |
| Clone through B hangs, then *Not found* | B could not fetch the QDN data within 60 s per resource; check B has peers and the resource status on B. Retry once the data has arrived. |
