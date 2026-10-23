# devnet setup

Description of what is actually done to build a working 3-node devnet/testnet.
We refer tol it as devnet here because it serves as both *rngit* testnet and it is used to deveolp a rngit Q-app.

Reference.: see [DEVNET-SETUP](https://github.com/jschulthess/qortal/blob/reticulum-git/reticulum/rngit-livetest/DEVNET-SETUP.md) for a generic description.

## Prerequisites

* 3 test nodes setup (git, java, ...)
  => siddi-{1,2,3}.crowetic.com (used in make-devnet.sh command below)
* Open firewall between the nodes for the listen, data and Reticulum port(s), and the API port for whoever uses Hub.
  => 432{91,92,94} and for Reticulum 4232 on all 3 nodes

Note: Utility scripts for dev machine to build (ibuildlocal.sh) and deploy qortal.jar to devnet (ideploy_jar_devnet.sh).

``` shell
# ibuildlocal.sh
cat << EOF > ibuildlocal.sh
mvn clean package -DskipTests     # -q
cp -f target/qortal-*.jar qortal.jar
date && ls -l qortal.jar
EOF
chmod u+x ibuildlocal.sh
...

# ideploy_jar_devnet.sh
# Note: s{1,2,3}cc below are ~/.ssh/config "Host" entries (HostName <ip>, Port 22022 User <user>, ForwardAgent yes)
#       with ssh public key distributed to nodes
cat << EOF > ideploy_jar_devnet.sh
#!/usr/bin/env bash

echo "doing local build with copying of tartget/qortal*.jar to qortal.jar"
./ibuildlocal.sh
echo "local: build done, qortal.jar up to date"
echo "copying jar to remote nodes..."
echo "devnet-node-1: " && scp qortal.jar s1cc:/qortal-devnet/qortal.jar
echo "devnet-node-2: " && scp qortal.jar s2cc:/qortal-devnet/qortal.jar
echo "devnet-node-3: " && scp qortal.jar s3cc:/qortal-devnet/qortal.jar
echo "deployment done"
EOF
chmod u+x ideploy_jar_devnet.sh
```

## Prepare Nodes

On each test node:

* create /qortal-devnet (change ownership to siddi:siddi)
* copy start.sh and stop.sh from repo clone to nodes /qortal-dev/ for easy start/stop

## Create Devnet

Fees and reward-share limit are mainnet's current values: 0.01 QORT a transaction, 1.25 QORT a name.

Genesis contains (by default): Note - accounts changeable in generation command with *--account NAME:QORT*

* minters: minter{1,2,3} (one per node). Each is a founder (level 10) with self-share and admin of group devnet-minters (group 1) and holds 1'000 Qort
* funded accounts: *faucet* (1'000'000 QORT), *publisher* and *devuser* (10'000 QORT), *dev1{1,2,3}* (1'000 QORT)

``` shell
# on any node with checkout of git@github.com:jschulthess/qortal.git, branch "reticulum-git" (eg. my dev laptop)
# create:
cd reticulum/rngit-livetest/
./make-devnet.sh --out ~/devnet --api-port 43291 --listen-port 43292 --data-port 43294 \
  --reticulum-port 4232 \
  --hosts siddi-1.crowetic.com,siddi-2.crowetic.com,siddi-3.crowetic.com --start-minutes 20
```

Above writes to ~/devnet/:

* devchain.json: chain config derived from mainnet blockchain.json
* devnet-keys.json: every genesis account's address and keys (SECRETS)
* settings-node-{1,2,3}.json: settings.json files for the 3 nodes

## Deploy and Start Nodes

``` shell
# on development machine (eg. my dev laptop), after building the 
./ideploy_jar_devnet.sh

# copy settings files to nodes
scp ~/devnet/settings-node-1.json s1cc:/qortal-devnet/settings.json
scp ~/devnet/devchain.json s1cc:/qortal-devnet/
#
scp ~/devnet/settings-node-2.json s2cc:/qortal-devnet/settings.json
scp ~/devnet/devchain.json s2cc:/qortal-devnet/
#
scp ~/devnet/settings-node-3.json s3cc:/qortal-devnet/settings.json
scp ~/devnet/devchain.json s3cc:/qortal-devnet/

# Note: generator sets siddi-1 to enable a BackboneServerInterface ("reticulumHasServerInterface": true) and the other nodes to point to it.
#       ("reticulumBackboneGatewayServers" : [ "siddi-1.crowetic.com:4232" ])

# ssh into _each_ node and start qortal:
cd /qortal-devnet
./start.sh
# => check in qortal.log:
tail -f qortal.log
# => "Using blockchain config file: devchain.json", then "Syncing from genesis block!"
# => _no_ "Failed to render config file" (from RNSConfigWriter) <=> if it does stop node, remove .reticulum/ and fix setting file before starting again.
#
# generate an API key (api.txt) once.
API=http://localhost:43291
KEY=$(curl -s -X POST $API/admin/apikey/generate)        # only works before a key exists
```

## Post Tasks

For "Start Minting" and "Accounts and Names" see [DEVNET-SETUP](https://github.com/jschulthess/qortal/blob/reticulum-git/reticulum/rngit-livetest/DEVNET-SETUP.md)

1. start minting. For any of the pre-defined minters in "devnet-keys.json" do:

``` shell
# start minting for minter{1,2,3}
for i in {1,2,3}; do
  read MKEY < <(python3 -c "import json; a = json.load(open('$HOME/devnet/devnet-keys.json'))['minter$i']; print(a['mintingKey'])")
  echo "adding account key for minter$i: $MKEY"
  curl -s -X POST -H "X-API-KEY: $KEY" $API/admin/mintingaccounts -d "$MKEY"; echo
done
# check: all three listed
curl -s -H "X-API-KEY: $KEY" $API/admin/mintingaccounts
```

If the height stays at 1, see the troubleshooting list under "Start minting" in
DEVNET-SETUP.md. In short: `fixedNetwork` must hold IPs (older generated
settings had hostnames, which reject every inbound connection), and settings need
`"recoveryModeTimeout": 60000` when the nodes start more than 90s after genesis.

2. register name "devgit" for account "publisher"

``` shell
read PUB PRIV ADDR < <(python3 -c "import json; a = json.load(open('$HOME/devnet/devnet-keys.json'))['publisher']; print(a['publicKey'], a['privateKey'], a['address'])")
REF=$(curl -s $API/addresses/lastreference/$ADDR)
RAW=$(curl -s -X POST -H "Content-Type: application/json" $API/names/register \
  -d "{\"timestamp\":$(date +%s%3N),\"reference\":\"$REF\",\"fee\":1.25,\"registrantPublicKey\":\"$PUB\",\"name\":\"devgit\",\"data\":\"\"}")
SIGNED=$(curl -s -X POST -H "Content-Type: application/json" -H "X-API-KEY: $KEY" $API/transactions/sign \
  -d "{\"privateKey\":\"$PRIV\",\"transactionBytes\":\"$RAW\"}")
curl -s -X POST -H "X-API-KEY: $KEY" $API/transactions/process -d "$SIGNED"    # true

#=> on next block (~1min), check "/names/devgit" (new) and "/names/devuser" (existing) API endpoint <=> should list details
curl -s -X GET -H "Content-type: application/json" http://localhost:$ADDR/names/devgit | jq .
curl -s -X GET -H "Content-type: application/json" http://localhost:$ADDR/names/devuser | jq .
```

3. to pay new developers from "faucet":

``` shell
# sample payment to devgit
read PUB PRIV ADDR < <(python3 -c "import json; a = json.load(open('$HOME/devnet/devnet-keys.json'))['faucet']; print(a['publicKey'], a['privateKey'], a['address'])")
REF=$(curl -s $API/addresses/lastreference/$ADDR)
RECIPIENT=<recipient-account-address>    # <recipient-account-address>, here since "devgit" is regisered by "publisher" => publisher address
AMOUNT=21                                # amount in QORT
RAW=$(curl -s -X POST -H "Content-Type: application/json" $API/payments/pay \
  -d "{\"timestamp\":$(date +%s%3N),\"reference\":\"$REF\",\"fee\":0.1,\"recipient\":\"$RECIPIENT\",\"senderPublicKey\":\"$PUB\",\"amount\":\"$AMOUNT\"}")
SIGNED=$(curl -s -X POST -H "Content-Type: application/json" -H "X-API-KEY: $KEY" $API/transactions/sign \
  -d "{\"privateKey\":\"$PRIV\",\"transactionBytes\":\"$RAW\"}")
curl -s -X POST -H "X-API-KEY: $KEY" $API/transactions/process -d "$SIGNED"    # true

#=> after ~1min devgit should have the AMMOUNT
BALANCE=$(curl http://localhost:12391/addresses/mintership/$RECIPIENT 2>/dev/null | jq '.balance')
echo "balance ($RECIPIENT): $BALANCE"
```

## Qortal Hub

Standard Qortal Hub mayb be used, no custom build is necessary.

``` shell
# ssh tunnel to one of the nodes
ssh -g -l siddi -L 43291:localhost:43291 s1cc

# start hub and add a host "http://localhost:43291" and add the api.key of that node (generated earlier in this document).
```

To authenticate users one of the pre-defined accounts may be used:

``` shell
# use hub-wallet.py to extract the info from ~/devnet/devnet-keys.json and create an encrypted json file to import in Hub.
rngit-livetest/hub-wallet.py create --keys ~/devnet/devnet-keys.json --account devuser -o devuser.json
rngit-livetest/hub-wallet.py decrypt devuser.json    # address and keys; reads Hub's version 2 files too
# - Password: prompted for, or taken from HUB_WALLET_PASSWORD.
```

## Test

Go through the [DEVNET-RUNBOOK](https://github.com/jschulthess/qortal/blob/reticulum-git/reticulum/rngit-livetest/DEVNET-RUNBOOK.md), steps 1-12.

