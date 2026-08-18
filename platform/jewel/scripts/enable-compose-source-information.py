#!/usr/bin/env python3
"""Add Compose sourceInformation=true to Jewel and DevKit Compose Bazel kotlinc options."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OPTION = "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"
EXISTING = "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true"

BUILD_FILES = [
    ROOT / "platform/jewel/foundation/BUILD.bazel",
    ROOT / "platform/jewel/ui/BUILD.bazel",
    ROOT / "platform/jewel/ui-tests/BUILD.bazel",
    ROOT / "platform/jewel/int-ui/int-ui-standalone/BUILD.bazel",
    ROOT / "platform/jewel/ide-laf-bridge/BUILD.bazel",
    ROOT / "platform/jewel/samples/showcase/BUILD.bazel",
    ROOT / "platform/jewel/samples/standalone/BUILD.bazel",
    ROOT / "plugins/devkit/intellij.devkit.compose/BUILD.bazel",
]


def patch(text: str) -> str:
    if OPTION in text:
        return text
    text = text.replace(
        f'plugin_options = ["{EXISTING}"]',
        "plugin_options = [\n"
        f'        "{EXISTING}",\n'
        f'        "{OPTION}",\n'
        "    ]",
    )
    if OPTION in text:
        return text
    # Insert plugin_options after each create_kotlinc_options name= line when missing.
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        out.append(lines[i])
        if lines[i].startswith("create_kotlinc_options("):
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith(")"):
                block.append(lines[i])
                i += 1
            block_text = "".join(block)
            if "plugin_options" not in block_text:
                # insert after name = ...
                inserted = False
                for j, bl in enumerate(block):
                    out.append(bl)
                    if (not inserted) and "name =" in bl:
                        indent = "    "
                        out.append(f'{indent}plugin_options = ["{OPTION}"],\n')
                        inserted = True
                if not inserted:
                    out.extend(block)
            else:
                out.extend(block)
            continue
        i += 1
    return "".join(out)


def main() -> None:
    for path in BUILD_FILES:
        original = path.read_text()
        updated = patch(original)
        if updated != original:
            path.write_text(updated)
            print(f"patched {path.relative_to(ROOT)}")
        else:
            print(f"unchanged {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
