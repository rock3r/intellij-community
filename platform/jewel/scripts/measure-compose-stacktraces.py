#!/usr/bin/env python3
"""Measure Bazel compile time and Compose source-information bytecode for Jewel targets.

Bytecode metrics distinguish:
- ComposerKt.sourceInformation (the diagnostic API; emitted when sourceInformation=true)
- sourceInformationMarkerStart (also present from inlined Compose library code)
- encoded `.kt#` source-info strings, split into Compose-library vs own-file (Jewel) strings
"""

from __future__ import annotations

import json
import os
import statistics
import struct
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
BAZEL = ROOT / "bazel.cmd"
COMPOSE_LIBRARY_KT = frozenset(
    {
        "Box.kt",
        "Column.kt",
        "Composables.kt",
        "Composer.kt",
        "CompositionLocal.kt",
        "Layout.kt",
        "Padding.kt",
        "Row.kt",
        "Size.kt",
        "Spacer.kt",
        "Offset.kt",
        "Alignment.kt",
        "Modifier.kt",
        "Color.kt",
        "SnapshotState.kt",
    }
)


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


def bazel_info(key: str) -> str:
    proc = run(["bash", str(BAZEL), "info", key])
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"bazel info {key} failed")
    for line in reversed(proc.stdout.splitlines()):
        line = line.strip()
        if line and not line.startswith("INFO:") and not line.startswith("WARNING:"):
            return line
    raise SystemExit(f"bazel info {key} produced no value")


def bazel_build(nocache: bool) -> float:
    cmd = ["bash", str(BAZEL), "build", *TARGETS]
    if nocache:
        cmd.extend(
            [
                "--nouse_action_cache",
                "--disk_cache=",
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
    return elapsed


def is_product_jar(path: Path) -> bool:
    name = path.name
    if path.suffix != ".jar":
        return False
    if name.endswith(".abi.jar") or name.endswith("-hjar.jar") or name.endswith(".srcjar"):
        return False
    if "srcjar" in name or "kotlinCriStorage" in name:
        return False
    return True


def resolve_cquery_path(line: str, execroot: Path) -> Path | None:
    raw = line.strip()
    if not raw:
        return None
    if raw.startswith("INFO:") or raw.startswith("DEBUG:") or raw.startswith("WARNING:"):
        return None
    if raw.startswith("Loading:") or raw.startswith("Analyzing:") or raw.startswith("Computing"):
        return None
    path = Path(raw)
    if not path.is_absolute():
        candidate = execroot / path
        if candidate.exists():
            return candidate
        candidate = ROOT / path
        if candidate.exists():
            return candidate
        return None
    return path if path.exists() else None


def iter_class_bytes() -> list[tuple[str, str, bytes]]:
    execroot = Path(bazel_info("execution_root"))
    results: list[tuple[str, str, bytes]] = []
    for target in TARGETS:
        proc = run(["bash", str(BAZEL), "cquery", "--output=files", target])
        if proc.returncode != 0:
            sys.stderr.write(proc.stderr)
            raise SystemExit(f"bazel cquery failed for {target}")
        for line in proc.stdout.splitlines():
            path = resolve_cquery_path(line, execroot)
            if path is None or not is_product_jar(path):
                continue
            try:
                with zipfile.ZipFile(path) as zf:
                    for name in zf.namelist():
                        if name.endswith(".class"):
                            results.append((path.name, name, zf.read(name)))
            except zipfile.BadZipFile:
                continue
    return results


def utf8_constants(class_bytes: bytes) -> list[str]:
    if len(class_bytes) < 10 or class_bytes[:4] != b"\xca\xfe\xba\xbe":
        return []
    cp_count = struct.unpack_from(">H", class_bytes, 8)[0]
    offset = 10
    strings: list[str] = []
    i = 1
    try:
        while i < cp_count:
            tag = class_bytes[offset]
            offset += 1
            if tag == 1:
                length = struct.unpack_from(">H", class_bytes, offset)[0]
                offset += 2
                raw = class_bytes[offset : offset + length]
                offset += length
                strings.append(raw.decode("utf-8", errors="replace"))
            elif tag in (7, 8, 16, 19, 20):
                offset += 2
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                offset += 4
            elif tag in (5, 6):
                offset += 8
                i += 1
            elif tag == 15:
                offset += 3
            else:
                break
            i += 1
    except (IndexError, struct.error):
        return strings
    return strings


def own_source_file(class_name: str) -> str | None:
    base = class_name.rsplit("/", 1)[-1].removesuffix(".class")
    if "$" in base:
        base = base.split("$", 1)[0]
    if not base:
        return None
    if base.endswith("Kt"):
        return f"{base[:-2]}.kt"
    return f"{base}.kt"


def encoded_kt_file(text: str) -> str | None:
    idx = text.rfind(".kt#")
    if idx < 0:
        return None
    start = text.rfind(":", 0, idx)
    if start < 0:
        return None
    return text[start + 1 : idx + 3]


def count_markers(classes: list[tuple[str, str, bytes]]) -> dict[str, object]:
    class_files = 0
    total_class_bytes = 0
    source_information_methods = 0
    marker_start_methods = 0
    encoded_kt_hash = 0
    encoded_own_file = 0
    encoded_library = 0
    files_with_source_information_method = 0
    files_with_own_file_kt_hash = 0
    per_jar: dict[str, dict[str, int]] = {}

    for jar_name, class_name, data in classes:
        class_files += 1
        total_class_bytes += len(data)
        jar = per_jar.setdefault(
            jar_name,
            {
                "classFiles": 0,
                "bytes": 0,
                "sourceInformationMethods": 0,
                "markerStartMethods": 0,
                "encodedKtHash": 0,
                "encodedOwnFileKtHash": 0,
            },
        )
        jar["classFiles"] += 1
        jar["bytes"] += len(data)
        constants = utf8_constants(data)
        if not constants:
            continue
        own = own_source_file(class_name)
        has_source_information = False
        has_own_file = False
        for text in constants:
            if text == "sourceInformation":
                source_information_methods += 1
                jar["sourceInformationMethods"] += 1
                has_source_information = True
            elif text == "sourceInformationMarkerStart":
                marker_start_methods += 1
                jar["markerStartMethods"] += 1
            kt_file = encoded_kt_file(text)
            if kt_file is None:
                continue
            encoded_kt_hash += 1
            jar["encodedKtHash"] += 1
            if own is not None and kt_file == own:
                encoded_own_file += 1
                jar["encodedOwnFileKtHash"] += 1
                has_own_file = True
            elif kt_file in COMPOSE_LIBRARY_KT:
                encoded_library += 1
        if has_source_information:
            files_with_source_information_method += 1
        if has_own_file:
            files_with_own_file_kt_hash += 1

    return {
        "classFiles": class_files,
        "totalClassBytes": total_class_bytes,
        "sourceInformationMethodUtf8": source_information_methods,
        "classFilesWithSourceInformationMethod": files_with_source_information_method,
        "sourceInformationMarkerStartUtf8": marker_start_methods,
        "encodedKtHashStrings": encoded_kt_hash,
        "encodedOwnFileKtHashStrings": encoded_own_file,
        "encodedComposeLibraryKtHashStrings": encoded_library,
        "classFilesWithOwnFileKtHash": files_with_own_file_kt_hash,
        "jars": per_jar,
    }


def main() -> None:
    args = [a for a in sys.argv[1:] if a]
    bytecode_only = "--bytecode-only" in args
    args = [a for a in args if a != "--bytecode-only"]
    label = args[0] if args else "unspecified"
    repeats = int(args[1]) if len(args) > 1 else 3
    OUT.mkdir(parents=True, exist_ok=True)

    times: list[float] = []
    if not bytecode_only:
        print("Warmup build (may use cache)...", flush=True)
        bazel_build(nocache=False)
        for i in range(repeats):
            print(f"Timed build {i + 1}/{repeats} (action cache disabled)...", flush=True)
            elapsed = bazel_build(nocache=True)
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
