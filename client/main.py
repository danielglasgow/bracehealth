import signal
import sys
import traceback

from src.app import App


def main():
    app = App()
    signal.signal(signal.SIGINT, app.on_signal)
    try:
        app.start()
    except Exception as e:
        print("Error occurred:")
        traceback.print_exc()
        app.shutdown()
        sys.exit(1)


if __name__ == "__main__":
    main()
