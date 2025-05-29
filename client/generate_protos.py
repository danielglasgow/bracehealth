import os
import subprocess
import re


def generate_protos():
    os.makedirs("../generated", exist_ok=True)

    # Run the protoc command
    cmd = [
        "python",
        "-m",
        "grpc_tools.protoc",
        "-I../billing/src/main/proto",
        "--python_out=../generated",
        "--grpc_python_out=../generated",
        "../billing/src/main/proto/billing.proto",
    ]
    subprocess.run(cmd, check=True)

    # Fix the imports in the generated files
    grpc_file = "generated/billing_pb2_grpc.py"
    if os.path.exists(grpc_file):
        with open(grpc_file, "r") as f:
            content = f.read()

        # Replace the import statement
        content = re.sub(
            r"import billing_pb2 as billing__pb2",
            "from . import billing_pb2 as billing__pb2",
            content,
        )

        with open(grpc_file, "w") as f:
            f.write(content)


if __name__ == "__main__":
    generate_protos()
