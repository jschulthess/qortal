# An isolated Qortal devnet

How to build a private devnet of three nodes, with its own chain, accounts,
names and QDN, that cannot exchange anything with mainnet or with other test
networks. Hubs can connect to it and load Q-Apps from it, and developers can
build Q-Apps against it. Once it runs, `DEVNET-RUNBOOK.md` tests rngit on it.

`make-devnet.sh` generates everything a node needs; the rest of this page is
how to deploy it.

## What keeps it isolated

| Layer | What it does |
|---|---|
| `"isTestNet": true` | Testnet message magic, so mainnet peers drop the handshake; Reticulum app name `qortaltest` |
| Its own genesis (`devchain.json`) | A chain nobody else has: other networks cannot sync it, nor it theirs |
| Its own ports (default 6339x) | Distinct from the shared test network on 6239x |
| `fixedNetwork` | Each node talks only to the other devnet nodes |
| `"bootstrap": false` | Never downloads a public bootstrap |
| Reticulum `network_name` and a random `passphrase` | Interface access codes: other meshes cannot join; node 1 is the only gateway, never the public ones |
| Firewall | Peer ports open only between the devnet nodes; the API only to developers |

## 1. Generate the devnet

On any machine with a Core checkout (`reticulum-git` at or after the commit
that adds `make-devnet.sh`):

```bash
cd ~/git/qortal-claude/reticulum/rngit-livetest
# using defaults
./make-devnet.sh --out ~/devnet --hosts devnet-1.example,devnet-2.example,devnet-3.example --start-minutes 20

# other example using custom ports
./make-devnet.sh --out ~/devnet --api-port 43291 --listen-port 43292 --data-port 43294 \
  --hosts devnet-1.example,devnet-2.example,devnet-3.example --start-minutes 20
```

It writes to `~/devnet`:

| File | Content |
|---|---|
| `devchain.json` | The chain config, derived from Core's current mainnet `blockchain.json` |
| `devnet-keys.json` | Every genesis account's address and keys. Private: keep it out of git, mode 600 |
| `settings-node-1.json` … `-3.json` | One `settings.json` per node |

The chain follows mainnet's rules as they apply today:
- Every feature trigger mainnet has passed is active from genesis.
- Windows mainnet has closed (such as the reward-share pause) are empty.
- The two triggers mainnet keeps in the far future stay there.
- Fees and the reward-share limit are mainnet's current values: 0.01 QORT a
  transaction, 1.25 QORT a name.

Genesis contains:
- **Minters** `minter1`–`minter3`, one per node. Each is a founder at level 10
  with a self-share and an admin of the minting group `devnet-minters` (group 1),
  and holds 1,000 QORT.
- **Funded accounts:** `faucet` (1,000,000 QORT), `publisher` and `devuser`
  (10,000 each), `dev1`–`dev3` (1,000 each). Change them with `--account NAME:QORT`,
  repeated.

The genesis timestamp is `--start-minutes` from now, rounded to the minute.
Choose enough time to deploy all nodes; a node minted late only syncs from genesis.

Other options: `--api-port`, `--listen-port`, `--data-port` (defaults 63391,
63392, 63394), `--reticulum-port` (node 1's gateway, default 4240), `--network-name`,
`--passphrase`, and `--port-step N` to give each node ports N higher, for
several nodes on one machine. See *Known issues* before doing that.

## 2. Deploy the nodes

On each node `N`, in its own directory (not an existing mainnet or testnet one):

```bash
mkdir -p ~/qortal-devnet && cd ~/qortal-devnet
cp /path/to/qortal.jar .                # built from the same checkout
cp ~/devnet/devchain.json .
cp ~/devnet/settings-node-N.json settings.json
java -Djava.net.preferIPv4Stack=false -jar qortal.jar settings.json > run.out 2>&1 &
```

**Check** in `qortal.log`: `Using blockchain config file: devchain.json`, then
`Syncing from genesis block!`, and no `Failed to render config file` from
`RNSConfigWriter`. That error means the node fell back to Core's packaged
testnet Reticulum config, which dials a public host; stop the node and fix the
settings (see *Known issues*).

Open the firewall between the nodes for the listen, data and Reticulum ports,
and the API port for whoever uses Hub.

## 3. Start minting

Once each node's API answers, give it an API key and its minter's minting key:

```bash
API=http://localhost:63391
KEY=$(curl -s -X POST $API/admin/apikey/generate)        # only works before a key exists
curl -s -X POST -H "X-API-KEY: $KEY" $API/admin/mintingaccounts -d '<mintingKey of minterN>'
```

The minting key is `minterN.mintingKey` in `devnet-keys.json`, not its
`privateKey`.

**Check:** after the genesis timestamp, `curl -s $API/blocks/height` rises by
about one a minute, on every node, and `GET /blocks/last` shows the same
signature on all of them. With all minters online, `minterAddress` alternates
between them.

## 4. Accounts and names

Transactions go through any node's API: build, sign with the account's
private key, process. To register `devgit` for the publisher:

```bash
read PUB PRIV ADDR < <(python3 -c "import json; a = json.load(open('$HOME/devnet/devnet-keys.json'))['publisher']; print(a['publicKey'], a['privateKey'], a['address'])")
REF=$(curl -s $API/addresses/lastreference/$ADDR)
RAW=$(curl -s -X POST -H "Content-Type: application/json" $API/names/register \
  -d "{\"timestamp\":$(date +%s%3N),\"reference\":\"$REF\",\"fee\":1.25,\"registrantPublicKey\":\"$PUB\",\"name\":\"devgit\",\"data\":\"\"}")
SIGNED=$(curl -s -X POST -H "Content-Type: application/json" -H "X-API-KEY: $KEY" $API/transactions/sign \
  -d "{\"privateKey\":\"$PRIV\",\"transactionBytes\":\"$RAW\"}")
curl -s -X POST -H "X-API-KEY: $KEY" $API/transactions/process -d "$SIGNED"    # true
```

**Check:** after the next block, `GET /names/devgit` on any node shows the
publisher as owner. Do the same for `devuser`, and pay new developers from
`faucet` with `POST /payments/pay`, built, signed and processed the same way.

## 5. rngit

Follow `DEVNET-RUNBOOK.md` from step 1, with these nodes as A, B and C. The
generated settings already enable rngit; P is `publisher`, U is `devuser`.
The runbook's laptop Reticulum config (step 2) uses node 1's gateway, with this
devnet's `reticulumNetworkName` and `reticulumPassphrase` from the settings files.

## 6. Hub and Q-Apps

A stock Qortal Hub works with the devnet; no custom build is needed.

**Connect Hub to a node.** Core's API answers only localhost by default
(`apiWhitelist` is `127.0.0.1` and `::1`), so forward a node's API port over
SSH, to the port Hub's *Local node* uses:

```bash
ssh -N -L 12391:localhost:63391 devnet-1.example
```

In Hub, choose *Local node* (`http://127.0.0.1:12391`). Use *Import API key*
with a copy of that node's API key file, `apikey.txt` in the node directory.
The key was generated in step 3.

The alternative is a custom node `http://<node>:<apiPort>` with the API key.
But then the node's `apiWhitelist` must include the developer's address, and
the API travels unencrypted.

**Log in with a devnet account.** Hub imports an encrypted wallet file, the
same JSON its *Download account* saves. `hub-wallet.py` writes one for any
account in `devnet-keys.json`:

```bash
cd ~/git/qortal-claude/reticulum/rngit-livetest
./hub-wallet.py create --keys ~/devnet/devnet-keys.json --account devuser -o devuser.json
```

It prompts for the wallet password, or takes it from `HUB_WALLET_PASSWORD`.
In Hub, import `devuser.json` and log in with that password. The file is a
version 1 wallet: Hub uses its decrypted seed directly as the account's
private key, so it opens exactly the account in `devnet-keys.json`.
`./hub-wallet.py decrypt devuser.json` prints a wallet file's address and
keys, including wallets Hub created (version 2).

Use devnet accounts only. A wallet file holds the private key, protected only
by its password.

**Q-Apps.**
- **The rngit reference Q-App:** publish `reticulum/qapp/rngit` as an `APP`
  resource under a devnet name you own, then open it in Hub. Use Hub's
  publish screen with the directory zipped, or `POST /arbitrary/APP/<name>/zip`,
  signed and processed as in step 4.
- **A developer's own Q-App:** Hub's dev mode loads a Q-App from a local dev
  server (domain and port) or from a selected directory, against the
  connected devnet node.

**What does not follow the selected node.** Everything else in Hub uses the
selected node: `getBaseApi`/`createEndpoint` fall back to
`ext-node.qortal.link` only when no node is set. Two exceptions, in the Hub
checkout of October 2025:
- Cross-chain trading always asks `appnode.qortal.org`, a mainnet node.
  Don't trade on the devnet.
- The tutorials check `ext-node.qortal.link/admin/status`. This is harmless.

## 7. Reset

Stop every node, delete `db-devnet` (and `.reticulum_test`, `rngit`,
`qortal-backup`) in each node directory, regenerate with `make-devnet.sh`, and
start again. A new genesis means new keys: Hubs need the new accounts.

If the chain stalls (every minter was offline), start one node with
`"singleNodeTestnet": true` until it has minted up to now, then remove the
setting and restart it. The others catch up, or use
`POST /admin/forcesync <peer>` on a stuck one.

## Known issues

- **Fresh databases need a Core fix.** On a new repository without a bootstrap,
  Core stopped during startup with `statement is not in batch mode`
  (`ArbitraryDataCacheManager.populateLatestSignaturesIfNecessary` ran an
  empty batch). The node stays running without an API. Fixed in `reticulum-git`
  with the commit adding this page.
- **Empty lists in `settings.json` are `null`.** An empty JSON array for a
  Reticulum gateway list used to make the config render fail, and Core then
  wrote its packaged testnet config, which has an AutoInterface and a TCP
  client to the public host `phantom.mobilefabrik.com`. `RNSConfigWriter`
  now treats a missing list as empty, in the same commit. On an older jar, never
  write `[]` for those settings.
- **One node per machine (or container).** Several nodes on one machine share
  Reticulum's local instance; when one stops, another loses its link and
  exits. A local test with `--port-step` ran two nodes reliably, the third
  exited this way.
- **`testnet/testchain.json` is out of date.** It predates `mintingGroupIds`
  and three feature triggers; generate the chain with `make-devnet.sh` instead.

## Verified so far

On one machine, 2026-10-03 (`--port-step 10`), with this page's Core fixes:
- The chain started from a generated genesis.
- Two nodes minted alternately and stayed on the same tip.
- A name registered through the API with the generated publisher key.

2026-10-10: `hub-wallet.py create` turns a generated key into a wallet file
that decrypts to the same account with Hub's own libraries and derivation
(bcryptjs, asmcrypto, Hub's `nacl-fast`), and Hub's MAC check rejects a wrong
password.

Not yet verified: three separate machines, logging in to a running Hub against
the devnet, and the rngit runbook on it.
