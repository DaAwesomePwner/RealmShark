#!/bin/bash
# Runs this checkout's Gradle wrapper in the logged-in macOS desktop (Aqua) session, so Swing/AWT
# tests and launches get a display when the caller reached the Mac over SSH (a "Background" launchd
# session, where AWT is headless). Builds without UI need no wrapper.
#
#   scripts/mac/gui-gradle.sh test --tests 'tomato.gui.chat.*'
#
# Desktop runs share one screen and keyboard focus, so they are serialized across all worktrees.
# Only JAVA_HOME and the arguments reach the desktop run; pass settings as -P/-D arguments.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd -P)
state=${REALMSHARK_GUI_STATE:-$HOME/Library/Caches/RealmShark/gui-gradle}
lock=$state/desktop.lock
# Gradle reuses only daemons whose JVM arguments match, so the marker keeps desktop-session daemons
# apart from headless ones started over SSH. The rest is Gradle 7.6's default daemon heap.
jvmargs="${REALMSHARK_GUI_JVMARGS:--Xmx512m -XX:MaxMetaspaceSize=384m} -Drealmshark.guiDaemon=true"
# Gradle itself holds the kernel lock, so it is released only when the build ends, even if this
# wrapper is killed.
gradle=(/usr/bin/lockf -k "$lock" /bin/sh ./gradlew "-Dorg.gradle.jvmargs=$jvmargs")

fail() { echo "gui-gradle: $*" >&2; exit 2; }

if [[ -z ${JAVA_HOME:-} ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) \
        || JAVA_HOME=$(brew --prefix openjdk@17 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home
fi
[[ -x $JAVA_HOME/bin/java ]] || fail "no JDK 17 found; set JAVA_HOME"
export JAVA_HOME

mkdir -p "$state"
# Runs whose wrapper was killed outright leave their directory behind.
find "$state" -maxdepth 1 -name 'run.*' -mtime +1 -exec rm -rf {} + 2>/dev/null || true
/usr/bin/lockf -s -k -t 0 "$lock" true || echo "gui-gradle: waiting for another desktop run to finish" >&2

if [[ $(launchctl managername 2>/dev/null) == Aqua ]]; then
    cd "$root"
    exec "${gradle[@]}" ${@+"$@"}
fi

run=$(mktemp -d "$state/run.XXXXXX")
# A unique name identifies this run's Terminal window.
command=$run/gui-gradle-${run##*.}.command
child=
shown=0

# Terminal keeps finished windows open by default; close this run's once its shell has exited.
close_window() {
    local tty
    tty=$(cat "$run/tty" 2>/dev/null) || return 0
    for _ in {1..10}; do
        [[ -n $child ]] && kill -0 "$child" 2>/dev/null || break
        sleep 0.5 || true
    done
    sleep 1 || true  # the login shell around the script logs out after it
    osascript - "$tty" "${command##*/}" > /dev/null 2>&1 << 'APPLESCRIPT' || true
on run argv
    tell application "Terminal"
        repeat with w in windows
            try
                if tty of selected tab of w is item 1 of argv and name of w contains item 2 of argv ¬
                    and not busy of selected tab of w then close w
            end try
        end repeat
    end tell
end run
APPLESCRIPT
}
trap 'close_window; rm -rf "$run"' EXIT

{
    echo '#!/bin/bash'
    # The wrapper claims the same directory if it gives up waiting, so a late start does nothing.
    printf 'mkdir %q 2>/dev/null || exit 0\n' "$run/claim"
    # A handler, unlike an ignored signal, lets Gradle receive Ctrl+C while this script reports it.
    echo 'trap : INT'
    printf 'tty > %q\n' "$run/tty"
    printf 'echo $$ > %q\n' "$run/pid"
    printf 'cd %q || { echo 125 > %q; exit 0; }\n' "$root" "$run/status"
    printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
    printf '%q ' "${gradle[@]}"
    (($# == 0)) || printf '%q ' "$@"
    printf '> %q 2>&1\n' "$run/log"
    printf 'echo $? > %q\n' "$run/status"
    # A clean exit lets Terminal close the window when its profile allows it.
    echo 'exit 0'
} > "$command"
chmod +x "$command"
: > "$run/log"

# Signals only set a flag; the loop below forwards them once the desktop run is known.
cancelled=
trap 'cancelled=1' INT TERM HUP

open -g -a Terminal "$command" || fail "could not open Terminal (run outside the command sandbox)"
for _ in {1..60}; do
    [[ ! -s $run/pid && -z $cancelled ]] || break
    sleep 0.5 || true
done
if [[ ! -s $run/pid ]] && mkdir "$run/claim" 2>/dev/null; then
    [[ -z $cancelled ]] || exit 130
    fail "Terminal did not start the run; is $USER logged in at the Mac's desktop?"
fi
for _ in {1..50}; do
    [[ ! -s $run/pid ]] || break
    sleep 0.2 || true
done
child=$(cat "$run/pid" 2>/dev/null) || fail "the desktop run stopped before starting Gradle"

flush() {
    local size
    size=$(stat -f %z "$run/log" 2>/dev/null) || return 0
    if ((size > shown)); then
        tail -c "+$((shown + 1))" "$run/log" | head -c "$((size - shown))" || true
        shown=$size
    fi
}

signalled=
while :; do
    if [[ -n $cancelled && -z $signalled ]]; then
        # The run script leads its own process group in Terminal; Gradle cancels on SIGINT.
        kill -INT -- "-$child" 2>/dev/null || true
        signalled=1
    fi
    flush
    [[ ! -s $run/status ]] || break
    kill -0 "$child" 2>/dev/null || { sleep 1 || true; flush; break; }
    sleep 1 || true
done
[[ -s $run/status ]] || fail "the desktop run ended without a status"
exit "$(cat "$run/status")"
