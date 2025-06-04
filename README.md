# Steps to run simulation

## Note on Maven

I've noticed sometimes I need to run `mvn clean install` twice, because the first time errors out. I beleive this is due to some race condition in proto-gen. I haven't taken the time to debug. If `mvn clean install` fails, give it a couple runs if you see an error like:

```
[ERROR] /Users/danielglasgow/src/bracehealth/shared/target/generated-sources/protobuf/java/com/bracehealth/shared/PayerClaimOuterClass.java: error reading /Users/danielglasgow/src/bracehealth/shared/target/generated-sources/protobuf/java/com/bracehealth/shared/PayerClaimOuterClass.java; /Users/danielglasgow/src/bracehealth/shared/target/generated-sources/protobuf/java/com/bracehealth/shared/PayerClaimOuterClass.java
```

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

(In another terminal)

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
