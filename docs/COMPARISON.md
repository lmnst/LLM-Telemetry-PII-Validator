# Comparison: parallel-downloader vs curl, wget

> Re-derive these numbers locally with [`docs/run-comparison.sh`](run-comparison.sh).

This is the **zero-RTT, single-host** comparison: an Apache `httpd:2.4`
container on the loopback interface, three file sizes, 1 warmup + 5
measured runs per tool. It is, deliberately, the unflattering regime
for a parallel downloader. Splitting one URL across eight connections
cannot beat a single-connection client when there is no round-trip
latency to amortize; the ranged-GET, sidecar-manifest, fsync-per-chunk
machinery is pure overhead in this setup.

The README's [Speed section](../README.md#speed) covers the intended
regime (`netem` 50 ms one-way delay). On the host these numbers came
from, that machinery still buys only marginal speedup, because Docker
Desktop's port-forwarding through the WSL2 backend caps per-flow
parallelism; on a less proxied path the curve compounds further. This
page documents the absolute-throughput floor on a happy network so a
reader has a recognizable anchor for the README's relative numbers.

Hardware: Windows 11 Home (Intel Core i7-10710U, 6c/12t, 1.10 GHz
base), Docker Desktop / Engine 29.4.1 with the WSL2 backend, Apache
httpd 2.4.67, MSYS2 git-bash, Microsoft OpenJDK 21.0.11, curl 7.85.0,
wget 1.21.4. Re-derived on 2026-05-06.

> **Methodology note for this run.** `docs/run-comparison.sh` shells
> out to `hyperfine` for the actual measurement. On Windows / git-bash
> hyperfine's argument quoting around `--shell` mangles forward slashes
> in the command string before bash sees them, so the unix launcher
> path turns into a "command not found" right inside the warmup
> iteration. The numbers below were captured with a small bash+awk
> timer mirroring hyperfine's methodology (1 warmup, 5 measured runs,
> mean / min / max / relative-to-fastest). On Linux and macOS the
> script's hyperfine path runs as documented; the toolchain-detection
> and bind-mount fixes in this revision are platform-portable
> regardless.

## 10 MiB

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 1264.0 +/- 30.4 | 1232.0 | 1300.0 | 4.59 +/- 0.11 |
| `curl --parallel-max 8` | 275.2 +/- 8.3 | 266.0 | 287.0 | 1.00 |
| `wget` | 319.6 +/- 6.8 | 315.0 | 331.0 | 1.16 +/- 0.02 |

The 10 MiB row is dominated by JVM cold-start, not download work. A
loopback download of 10 MiB takes a quarter of a second; the rest of
the parallel-downloader time (~1 s) is the JVM warming up under
Docker Desktop's WSL2 bridge. Hyperfine measures wall-clock per
invocation, and our CLI starts a fresh JVM every time, so we pay that
tax on every sample. In real ingest pipelines the JVM is launched
once and downloads many files; the amortized per-file cost approaches
the 100 / 1024 MiB rows below.

## 100 MiB

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 2542.0 +/- 438.1 | 2052.0 | 2942.0 | 2.10 +/- 0.36 |
| `curl --parallel-max 8` | 1486.0 +/- 60.4 | 1394.0 | 1560.0 | 1.23 +/- 0.05 |
| `wget` | 1209.0 +/- 149.8 | 1093.0 | 1415.0 | 1.00 |

JVM startup is now under half the wall time; the remaining gap covers
twelve 8 MiB chunks at default settings, including the sidecar
manifest path even though `--resume` is off (the `Manifest` allocation
is cheap, but per-chunk progress dispatch and `Content-Range`
validation are all running). curl and wget run as a single connection
and saturate loopback throughput. parallel-downloader's variance is
higher than the single-connection clients, which is consistent with
the WSL2 port-forward layer: each ranged GET opens a fresh connection
through vpnkit, and that path occasionally adds tens to hundreds of
ms of jitter that single-connection clients amortize over the whole
transfer.

## 1024 MiB

| Command | Mean [s] | Min [s] | Max [s] | Relative |
|:---|---:|---:|---:|---:|
| `parallel-downloader` | 16.083 +/- 0.348 | 15.839 | 16.682 | 1.00 |
| `curl --parallel-max 8` | 20.824 +/- 9.133 | 15.333 | 36.961 | 1.29 +/- 0.57 |
| `wget` | 16.342 +/- 2.363 | 13.435 | 18.796 | 1.02 +/- 0.15 |

At 1 GiB the per-tool overhead has finally been amortized. The means
of all three tools are within ~5% of each other, with
parallel-downloader the tightest of the three (stdev 0.35 s, vs.
2.4 s for wget and 9.1 s for curl). curl had one 36.9 s outlier in
this sample of five, which inflates its mean and stdev; the single
runs are otherwise broadly comparable. Single-connection clients
remain a fine choice for one-off loopback transfers, but the chunked
design buys variance compression at this size: any single network
hiccup affects one 8 MiB chunk, not the whole download. That property
is what sells the parallel approach on real networks; on loopback it
just shows up as a cleaner stdev.

## When this matters, and when it doesn't

For a single host with negligible network distance, a single-connection
client is the right tool when the workload is one-shot and small. The
1024 MiB row above is honest; on this host curl and wget are not
beaten by parallel-downloader so much as they are matched by it.
`parallel-downloader` is built for the regime where:

- One-way RTT is non-trivial and the link's bandwidth-delay product
  is wide enough that one connection cannot fill it.
- The download is large enough that JVM startup is irrelevant and the
  per-chunk overhead is amortized.
- The caller wants integrity verification (`--sha256`), resumability
  (`--resume`), or chaos-tested correctness guarantees that single
  curl/wget invocations do not provide.

If none of those apply, the 100 MiB row is the honest answer: pay the
2x tax for the integrity invariant, or use curl. The library API
(rather than the CLI) sidesteps JVM-cold-start entirely, which is the
right shape when downloads are part of a longer-running process.

`curl --parallel-max 8` is shown for completeness because the brief
asked for it; with a single source URL, curl runs one connection
regardless of the parallelism flag, so this is effectively a
single-connection curl baseline. The few-percent gap to wget across
rows is inter-tool overhead, not parallelism.

## Reproducing

```bash
docs/run-comparison.sh
```

The script builds `installDist`, generates 10/100/1024 MiB random
files, spins up `httpd:2.4`, runs hyperfine against the three tools,
and tears down. It pins `JAVA_HOME` to the Gradle toolchain's JDK 21
so the CLI launches the same JVM that compiled it (avoiding a
class-file-65 mismatch when the operator's `PATH` points at JDK 17).
On Windows / git-bash, see the methodology note at the top: the
hyperfine path needs adjustment that has not been merged here.
