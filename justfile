default:
    @just --list

# Run the fast suite: unit + property + in-process integration tests. No Docker, no network.
test:
    ./gradlew test

# Run the chaos property + resume tests (in-process; under a second).
chaos:
    ./gradlew test -PchaosTests

# Run the Testcontainers integration suite. Requires a Docker host.
integration:
    ./gradlew test -PintegrationTests

# Spin up httpd, generate a 64 MiB corpus, download via the CLI with SHA-256 check, tear down.
demo:
    #!/usr/bin/env bash
    set -euo pipefail

    CORPUS=$(mktemp -d)
    OUT=$(mktemp -t demo-out.XXXXXX)
    cleanup() {
        docker rm -f dl-httpd-demo >/dev/null 2>&1 || true
        rm -rf "$CORPUS"
        rm -f "$OUT"
    }
    trap cleanup EXIT

    echo "[demo] Generating 64 MiB random corpus..."
    head -c $((64 * 1024 * 1024)) /dev/urandom > "$CORPUS/test.bin"

    if command -v sha256sum >/dev/null; then
        EXPECTED_SHA=$(sha256sum "$CORPUS/test.bin" | awk '{print $1}')
    else
        EXPECTED_SHA=$(shasum -a 256 "$CORPUS/test.bin" | awk '{print $1}')
    fi
    echo "[demo] SHA-256: $EXPECTED_SHA"

    echo "[demo] Starting httpd:2.4..."
    docker run --rm -d -p 8080:80 \
        -v "$CORPUS":/usr/local/apache2/htdocs/ \
        --name dl-httpd-demo httpd:2.4 >/dev/null

    # Wait for httpd to start accepting requests
    for i in {1..30}; do
        if curl -sf -o /dev/null http://localhost:8080/test.bin -I; then break; fi
        sleep 0.2
    done

    echo "[demo] Downloading via CLI..."
    ./gradlew run -q --args="--url http://localhost:8080/test.bin --out $OUT --sha256 $EXPECTED_SHA --report json"

    echo
    echo "[demo] OK — download complete and SHA-256 verified."
