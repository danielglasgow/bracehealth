# Run

```
mvn spring-boot:run
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
mvn test -Dtest=ClaimStoreTest
mvn test -Dtest=BillingServiceImplTest
```

(Weird race conditions, maybe, again)
