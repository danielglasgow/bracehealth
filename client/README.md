# Python gRPC Client

## Setup

1. Create a virtual environment:

```bash
python -m venv .venv --prompt bracehealth
```

2. Activate the virtual environment:

```bash
source .venv/bin/activate
```

3. Install dependencies:

```bash
pip install -r requirements.txt
```

4. Generate gRPC code:

```bash
python scripts/generate_protos.py
```

5. Run the client:

```bash
python main.py
```

## Deactivate

To exit the virtual environment:

```bash
deactivate
```

## Freeze Deps

```bash
pip freeze > requirements.txt
```
