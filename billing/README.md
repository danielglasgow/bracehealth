# Run

```
mvn spring-boot:run
```

Or to use "real" client:

```
mvn spring-boot:run -Dspring-boot.run.arguments="--clearinghouse.mode=grpc"
```

# Compile

```
mvn clean compile
```

Note: I've been having to run `mvn clean` followed by `mvn compile` due to weird race condition in proto-gen:
https://chatgpt.com/share/6838c135-4480-8013-a9dd-ff70445b9a0f

# Test

```
mvn test
```

or

```
mvn test -Dtest=<TargetClass>
```

e.g

```
mvn test -Dtest=BillingServiceTest
```

or to run a single test case:

```
mvn test -Dtest=BillingServiceTest#submitPatientPayment_amountExceedsBalance
```
