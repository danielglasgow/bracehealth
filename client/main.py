import grpc
from generated import billing_pb2
from generated import billing_pb2_grpc


def run():
    # Create a gRPC channel
    with grpc.insecure_channel("localhost:9090") as channel:
        # Create a stub (client)
        stub = billing_pb2_grpc.BillingServiceStub(channel)

        # Create a request
        request = billing_pb2.HelloRequest(name="World")

        # Make the call
        response = stub.hello(request)
        print("Response received:", response.greeting)


if __name__ == "__main__":
    run()
