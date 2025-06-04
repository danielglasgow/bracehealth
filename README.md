# Steps to run simulation

## First time setup

```
brew install maven
```

```
brew install pyenv
```

### Setup client python env

```
cd client
pyenv install 3.11.9
pyenv local 3.11.9
python -m venv .venv --prompt bracehealth
```

Ensure you have a python

## Running simulation

### Generate "shared" deps jar

```
cd shared
mvn clean install
```

### Run the billing service

```
cd billing
mvn clean install
mvn spring-boot:run
```

### Run client

(in another terminal)

(Also see client/README.md)

```
cd client
```

```
# First time only
pip install -r requirements.txt
python scripts/generate_protos.py
```

```
python main,py
```

## Other

It's also possible to run a mock clearinghouse (rather than in memory clearing house).

For that setup, simply:

```
cd clearinghouse
mvn clean install
mvn spring-boot:run
```

And then when you run the billing service, have it talk to the clearinghouse:

```
mvn spring-boot:run -Dspring-boot.run.arguments="--clearinghouse.mode=grpc"
```
