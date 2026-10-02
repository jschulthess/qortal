#!/usr/bin/env bash
#
# Live test of Core's rngit node (org.qortal.rngit) against the stock rngit
# and git-remote-rns clients from Python RNS.
#
# The server runs as RngitLiveNode: an RngitServer on its own Reticulum
# instance, without the rest of Core. Two client identities:
#   alice  may create repositories in group "public" (c:alice) and becomes
#          admin of what she creates
#   bob    may only read (r:all)
#
#   1  alice: rngit create public/repo            -> created, .allowed = adm:alice
#   2  alice: push a local repo (3 MiB blob)       -> server ref updated
#   3  bob:   clone                                -> same HEAD and blob
#   4  bob:   push                                 -> refused, server unchanged
#   5  alice: push a branch, then delete it        -> ref created, then removed
#   6  alice: rngit create public/repo again       -> "already exists"
#   7  bob:   rngit create public/other            -> refused, nothing created
#
# Usage:  ./run.sh       (RNS_SRC defaults to ~/git/Reticulum)
#
# Everything runs on 127.0.0.1:42508. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"
WORK="$HERE/.work"
PIDS=()

cleanup() {
    for p in "${PIDS[@]:-}"; do
        [[ -n "$p" ]] && kill "$p" 2>/dev/null
    done
}
trap cleanup EXIT

say()  { printf '\n=== %s ===\n' "$1"; }
pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; FAILURES=$((FAILURES+1)); }
FAILURES=0

rm -rf "$WORK" "$HERE/java_config/storage" "$HERE/python_client_config/storage"
mkdir -p "$WORK/bin" "$WORK/groups/public" "$WORK/rngit" "$WORK/alice" "$WORK/bob"

say "Preparing"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
export PYTHONPATH="$RNS_SRC"
echo "Reference RNS: $(python3 -c 'import RNS._version as v; print(v.__version__)')"

(cd "$CORE" && mvn -o -q test-compile -DskipJUnitTests=true) || { echo "FAIL: test-compile"; exit 1; }
(cd "$CORE" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$WORK/classpath.txt") || { echo "FAIL: classpath"; exit 1; }
CP="$(cat "$WORK/classpath.txt"):$CORE/target/classes:$CORE/target/test-classes"

# The stock client entry points, exactly as RNS installs them
for cmd in rngit git-remote-rns; do
    module=$([[ $cmd == rngit ]] && echo server || echo client)
    cat > "$WORK/bin/$cmd" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="$RNS_SRC"
exec python3 -c 'import sys; from RNS.Utilities.rngit.$module import main; sys.argv[0] = "$cmd"; sys.exit(main())' "\$@"
EOF
    chmod +x "$WORK/bin/$cmd"
done
export PATH="$WORK/bin:$PATH"
export RNS_CONFIG="$HERE/python_client_config"
export GIT_AUTHOR_NAME=livetest GIT_AUTHOR_EMAIL=livetest@example.invalid
export GIT_COMMITTER_NAME=livetest GIT_COMMITTER_EMAIL=livetest@example.invalid

# Client identities, created up front so the server config can name them
ident() {
    python3 -c "import RNS; i = RNS.Identity(); i.to_file('$1'); print(i.hash.hex())"
}
ALICE="$(ident "$WORK/alice/client_identity")"
BOB="$(ident "$WORK/bob/client_identity")"
echo "alice $ALICE"
echo "bob   $BOB"

cat > "$WORK/rngit/config" <<EOF
[rngit]
  node_name = Core rngit live test
  announce_interval = 1

[repositories]
  public = $WORK/groups/public

[aliases]
  alice = $ALICE

[access]
  public = r:all, c:alice
EOF

as() {  # as <who> <command...>: run a client command with that identity
    local who="$1"; shift
    RNGIT_CONFIG="$WORK/$who" "$@"
}

timeout 900 java -cp "$CP" org.qortal.rngit.RngitLiveNode "$HERE/java_config" "$WORK/rngit" \
    > "$WORK/server.log" 2>&1 &
PIDS+=($!)

DEST=""
for _ in $(seq 1 60); do
    DEST="$(grep -a '\[rngit-live\] destination <' "$WORK/server.log" 2>/dev/null \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST" ]] && break
    sleep 0.5
done
[[ -n "$DEST" ]] || { echo "FAIL: server did not start"; tail -30 "$WORK/server.log"; exit 1; }
echo "Java rngit destination: $DEST"
sleep 4
URL="rns://$DEST/public/repo"
REPO="$WORK/groups/public/repo"

# ---------------------------------------------------------------------------
say "1: alice creates public/repo"
as alice rngit create --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/1.log" 2>&1
if [[ -f "$REPO/HEAD" && "$(cat "$REPO.allowed" 2>/dev/null)" == "adm:$ALICE" ]] && grep -q "created" "$WORK/1.log"; then
    pass "bare repository created, creator is admin"
else
    sed 's/^/  | /' "$WORK/1.log" | tail -5; fail "create"
fi

# ---------------------------------------------------------------------------
say "2: alice pushes a new local repository"
# Cloning a freshly created, empty repository fails against the reference
# rngit node too (it bundles a ref that does not exist yet), so this follows the
# documented workflow: an existing local repository, a remote added, a push.
C="$WORK/alice/clone"
git init -q -b master "$C"
echo "hello" > "$C/README.md"
head -c $((3 * 1024 * 1024)) /dev/urandom > "$C/blob.bin"
git -C "$C" add . && git -C "$C" commit -q -m "first commit"
git -C "$C" remote add origin "$URL"
as alice git -C "$C" push -q origin master > "$WORK/2.log" 2>&1
PUSHED="$(git -C "$C" rev-parse HEAD)"
if [[ "$(git -C "$REPO" rev-parse refs/heads/master 2>/dev/null)" == "$PUSHED" ]]; then
    pass "server master is ${PUSHED:0:12}"
else
    sed 's/^/  | /' "$WORK/2.log" | tail -5; fail "push"
fi

# ---------------------------------------------------------------------------
say "3: bob clones"
as bob git clone -q "$URL" "$WORK/bob/clone" > "$WORK/3.log" 2>&1
if [[ "$(git -C "$WORK/bob/clone" rev-parse HEAD 2>/dev/null)" == "${PUSHED:-x}" ]] \
   && cmp -s "$WORK/alice/clone/blob.bin" "$WORK/bob/clone/blob.bin"; then
    pass "bob has alice's commit and blob"
else
    sed 's/^/  | /' "$WORK/3.log" | tail -5; fail "read-only clone"
fi

# ---------------------------------------------------------------------------
say "4: bob tries to push"
if [[ -d "$WORK/bob/clone/.git" ]]; then
    echo "bob was here" >> "$WORK/bob/clone/README.md"
    git -C "$WORK/bob/clone" commit -q -am "bob's change"
    as bob git -C "$WORK/bob/clone" push -q origin master > "$WORK/4.log" 2>&1
    RC=$?
    if [[ $RC -ne 0 && "$(git -C "$REPO" rev-parse refs/heads/master)" == "${PUSHED:-x}" ]]; then
        pass "refused ($(grep -oE 'refused list: .*|Not allowed|Not found' "$WORK/4.log" | head -1)), server unchanged"
    else
        sed 's/^/  | /' "$WORK/4.log" | tail -5; fail "unauthorised push was not refused"
    fi
else
    fail "no clone for bob"
fi

# ---------------------------------------------------------------------------
say "5: alice pushes a branch and deletes it"
if [[ -d "$WORK/alice/clone/.git" ]]; then
    git -C "$WORK/alice/clone" branch feature
    as alice git -C "$WORK/alice/clone" push -q origin feature > "$WORK/5a.log" 2>&1
    CREATED="$(git -C "$REPO" rev-parse --verify -q refs/heads/feature)"
    as alice git -C "$WORK/alice/clone" push -q origin :feature > "$WORK/5b.log" 2>&1
    GONE="$(git -C "$REPO" rev-parse --verify -q refs/heads/feature)"
    if [[ "$CREATED" == "${PUSHED:-x}" && -z "$GONE" ]]; then
        pass "feature created via update_ref, then deleted"
    else
        sed 's/^/  | /' "$WORK/5a.log" "$WORK/5b.log" | tail -8; fail "branch create/delete"
    fi
else
    fail "no clone for alice"
fi

# ---------------------------------------------------------------------------
say "6: alice creates public/repo again"
as alice rngit create --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/6.log" 2>&1
if grep -qi "already exists" "$WORK/6.log"; then
    pass "refused: already exists"
else
    sed 's/^/  | /' "$WORK/6.log" | tail -5; fail "duplicate create"
fi

# ---------------------------------------------------------------------------
say "7: bob tries to create public/other"
as bob rngit create --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "rns://$DEST/public/other" > "$WORK/7.log" 2>&1
if [[ ! -e "$WORK/groups/public/other" ]] && grep -qiE "not allowed|not found" "$WORK/7.log"; then
    pass "refused ($(grep -oiE 'not allowed|not found' "$WORK/7.log" | head -1)), nothing created"
else
    sed 's/^/  | /' "$WORK/7.log" | tail -5; fail "unauthorised create"
fi

# ---------------------------------------------------------------------------
say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — stock rngit and git-remote-rns against Core's rngit node: 7 of 7."
else
    echo "FAIL — $FAILURES check(s) failed. Logs in $WORK/"
fi
exit $FAILURES
