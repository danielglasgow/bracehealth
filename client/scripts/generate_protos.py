#!/usr/bin/env python
"""
Generate Python gRPC stubs for all protos in ../shared/proto → ./generated
and rewrite the intra-package imports so they are relative.
"""

import os
import re
import subprocess
import sys
from pathlib import Path


PROTO_DIR = Path(__file__).resolve().parent / ".." / ".." / "shared" / "proto"
OUT_DIR = Path(__file__).resolve().parent / ".." / "generated"


def _compile_all_protos() -> None:
    OUT_DIR.mkdir(exist_ok=True)
    # Touch __init__.py so Python treats the folder as a package
    (OUT_DIR / "__init__.py").touch(exist_ok=True)

    proto_files = [str(p) for p in PROTO_DIR.glob("*.proto")]

    cmd = [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"-I{PROTO_DIR}",
        f"--python_out={OUT_DIR}",
        f"--grpc_python_out={OUT_DIR}",
        *proto_files,  # compile *all* of them at once
    ]
    subprocess.run(cmd, check=True)


IMPORT_RX = re.compile(r"^import (\w+)_pb2 as (\w+)__pb2$", re.MULTILINE)


def _patch_relative_imports() -> None:
    """Rewrite 'import foo_pb2 as foo__pb2' → 'from . import foo_pb2 …'."""
    for path in OUT_DIR.glob("**/*_pb2*.py"):  # both _pb2 and _pb2_grpc
        txt = path.read_text()
        new_txt = IMPORT_RX.sub(r"from . import \1_pb2 as \2__pb2", txt)
        if txt != new_txt:
            path.write_text(new_txt)


def generate_protos() -> None:
    _compile_all_protos()
    _patch_relative_imports()


if __name__ == "__main__":
    generate_protos()
