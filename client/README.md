# Python gRPC Client

## Setup

1. Create a virtual environment:

IMPORTANT: Must use Python 3.11

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

## Test

```
pytest src/test_claim_util.py -v
```

## Generate

```
python scripts/generate_claims.py 200  claims.txt
```

## Run

```
python main.py
```

## Notes on setting up python 3.11

# 0. Install pyenv

brew install pyenv

# 1. Install Python 3.11 with pyenv

pyenv install 3.11.9

# 2. Set it locally in your project directory

pyenv local 3.11.9 # creates a .python-version file

# 3. Verify pyenv shims are picking up the right Python

which python # should be something like ~/.pyenv/shims/python
python --version # should be 3.11.9

# 4. Create a virtual environment using that Python

python -m venv .venv --prompt bracehealth

# 5. Activate the virtual environment

source .venv/bin/activate

# 6. Confirm everything is working

which python # should be your_project/.venv/bin/python
python --version # should be 3.11.9
