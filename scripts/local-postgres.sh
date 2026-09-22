#!/usr/bin/env bash
#
# Starts a throwaway PostgreSQL 16 for the test suite, using the server binaries already
# installed by the postgresql-16 package. No Docker and no root: the cluster lives under
# /tmp and runs as whoever invokes this.
#
# This is the fallback for machines where the account cannot open the Docker socket.
# Docker is the documented path -- see docs/LOCAL_DEV.md.
#
#   ./scripts/local-postgres.sh start|stop|status|env
#
set -euo pipefail

PGBIN=${PGBIN:-/usr/lib/postgresql/16/bin}
PGPORT=${PGPORT:-55432}
PGDIR=${PGDIR:-/tmp/webhook-retry-engine-pg}
PGUSER_NAME=webhook
PGPASS=webhook

start() {
    if "$PGBIN/pg_isready" -h 127.0.0.1 -p "$PGPORT" -q 2>/dev/null; then
        echo "Already running on port $PGPORT."
        env_block
        return
    fi

    if [[ ! -s "$PGDIR/PG_VERSION" ]]; then
        echo "Initialising a cluster in $PGDIR ..."
        rm -rf "$PGDIR"
        mkdir -p "$PGDIR"
        printf '%s' "$PGPASS" > "$PGDIR.pw"
        "$PGBIN/initdb" -D "$PGDIR" -U "$PGUSER_NAME" --pwfile="$PGDIR.pw" -A md5 --encoding=UTF8 >/dev/null
        rm -f "$PGDIR.pw"
    fi

    # Unix sockets are off because the socket path can exceed the 107-byte limit, and fsync is
    # off because this cluster is disposable -- losing it on a power cut costs nothing.
    "$PGBIN/pg_ctl" -D "$PGDIR" -l "$PGDIR/server.log" -w start \
        -o "-p $PGPORT -c listen_addresses=127.0.0.1 -c unix_socket_directories= \
            -c fsync=off -c synchronous_commit=off -c full_page_writes=off"

    for db in webhook_delivery webhook_ingest; do
        PGPASSWORD=$PGPASS "$PGBIN/psql" -h 127.0.0.1 -p "$PGPORT" -U "$PGUSER_NAME" -d postgres -tAc \
            "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 \
            || PGPASSWORD=$PGPASS "$PGBIN/createdb" -h 127.0.0.1 -p "$PGPORT" -U "$PGUSER_NAME" "$db"
    done

    echo "PostgreSQL 16 ready on 127.0.0.1:$PGPORT"
    env_block
}

stop() {
    "$PGBIN/pg_ctl" -D "$PGDIR" -m fast -w stop 2>/dev/null || echo "Not running."
}

status() {
    "$PGBIN/pg_isready" -h 127.0.0.1 -p "$PGPORT"
}

env_block() {
    cat <<ENV

Point the suite at it:

  export TEST_DB_URL=jdbc:postgresql://127.0.0.1:$PGPORT/webhook_delivery
  export TEST_DB_USER=$PGUSER_NAME
  export TEST_DB_PASSWORD=$PGPASS
  ./mvnw clean verify
ENV
}

case "${1:-start}" in
    start)  start ;;
    stop)   stop ;;
    status) status ;;
    env)    env_block ;;
    *)      echo "usage: $0 start|stop|status|env" >&2; exit 2 ;;
esac
