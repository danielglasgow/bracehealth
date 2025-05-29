import os
import subprocess
import re


def generate_protos():
    os.makedirs("generated", exist_ok=True)

    cmd = [
        "python",
        "-m",
        "grpc_tools.protoc",
        "-I../billing/src/main/proto",
        "--python_out=generated",
        "--grpc_python_out=generated",
        "../billing/src/main/proto/billing.proto",
    ]
    subprocess.run(cmd, check=True)

    # Hard coded hack to fix imports in the generated files
    grpc_file = "generated/billing_pb2_grpc.py"
    if os.path.exists(grpc_file):
        with open(grpc_file, "r") as f:
            content = f.read()

        content = re.sub(
            r"import billing_pb2 as billing__pb2",
            "from . import billing_pb2 as billing__pb2",
            content,
        )

        with open(grpc_file, "w") as f:
            f.write(content)


if __name__ == "__main__":
    generate_protos()
