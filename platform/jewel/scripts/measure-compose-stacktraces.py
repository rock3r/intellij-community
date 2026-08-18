#!/usr/bin/env python3
"""Measure Bazel compile time and bytecode sourceInformation markers for Jewel targets."""

from __future__ import annotations

import json
import os
import statistics
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "out" / "compose-stacktraces"
TARGETS = [
    "//platform/jewel/foundation:foundation",
    "//platform/jewel/ui:ui",
    "//platform/jewel/int-ui/int-ui-standalone:jewel-intUi-standalone",
    "//platform/jewel/ide-laf-bridge:ide-laf-bridge",
    "//platform/jewel/samples/showcase:showcase",
    "//platform/jewel/samples/standalone:standalone",
    "//plugins/devkit/intellij.devkit.compose:compose",
]
MARKER = b"sourceInformation"
BAZEL = ROOT / "bazel.cmd"


def run(cmd: list[str], env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(
        cmd,
        cwd=ROOT,
        env=env or os.environ.copy(),
        text=True,
        capture_output=True,
        check=False,
    )


def bazel_build(nocache: bool) -> tuple[float, int]:
    cmd = [str(BAZEL), "build", *TARGETS]
    if nocache:
        cmd.extend(
            [
                "--nouse_action_cache",
                "--noremote_accept_cached",
                "--noremote_upload_local_results",
            ]
        )
    start = time.perf_counter()
    proc = run(cmd)
    elapsed = time.perf_counter() - start
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout)
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"bazel build failed with {proc.returncode}")
    return elapsed, proc.returncode


def iter_class_bytes() -> list[tuple[str, bytes]]:
    proc = run([str(BAZEL), "cquery", "--output=files", *TARGETS])
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit("bazel cquery failed")
    results: list[tuple[str, bytes]] = []
    for line in proc.stdout.splitlines():
        path = Path(line.strip())
        if not path.is_absolute():
            path = ROOT / path
        if not path.exists():
            continue
        if path.suffix == ".jar" and path.is_file():
            try:
                with zipfile.ZipFile(path) as zf:
                    for name in zf.namelist():
                        if name.endswith(".class"):
                            results.append((f"{path.name}!{name}", zf.read(name)))
            except zipfile.BadZipFile:
                continue
        elif path.suffix == ".class":
            results.append((str(path), path.read_bytes()))
    return results


def count_markers(classes: list[tuple[str, bytes]]) -> dict[str, int]:
    files_with_marker = 0
    marker_hits = 0
    total_class_bytes = 0
    for _, data in classes:
        total_class_bytes += len(data)
        hits = data.count(MARKER)
        if hits:
            files_with_marker += 1
            marker_hits += hits
    return {
        "classFiles": len(classes),
        "classFilesWithSourceInformationUtf8": files_with_marker,
        "sourceInformationUtf8Hits": marker_hits,
        "totalClassBytes": total_class_bytes,
    }


def main() -> None:
    label = sys.argv[1] if len(sys.argv) > 1 else "unspecified"
    repeats = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    OUT.mkdir(parents=True, exist_ok=True)

    print("Warmup build (may use cache)...", flush=True)
    bazel_build(nocache=False)

    times: list[float] = []
    for i in range(repeats):
        print(f"Timed build {i + 1}/{repeats} (action cache disabled)...", flush=True)
        elapsed, _ = bazel_build(nocache=True)
        times.append(elapsed)
        print(f"  {elapsed:.2f}s", flush=True)

    markers = count_markers(iter_class_bytes())
    payload = {
        "label": label,
        "targets": TARGETS,
        "timedBuildSeconds": times,
        "medianSeconds": statistics.median(times) if times else None,
        "meanSeconds": statistics.mean(times) if times else None,
        "bytecode": markers,
    }
    out_path = OUT / f"compile-{label}.json"
    out_path.write_text(json.dumps(payload, indent=2) + "\n")
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
