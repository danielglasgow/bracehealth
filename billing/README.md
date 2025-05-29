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
