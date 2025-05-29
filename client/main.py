import grpc
import sys
import os

# Add the generated protobuf files to the Python path
sys.path.append(os.path.join(os.path.dirname(__file__), "generated"))


def main():
    # Create a gRPC channel to the BillingService
    channel = grpc.insecure_channel("localhost:8002")

    # TODO: Import and use the generated gRPC stub
    # This will be implemented once we have the proto files and generated code

    print("Connected to BillingService on port 8002")


if __name__ == "__main__":
    main()
