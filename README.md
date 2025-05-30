# Steps to test

1. Run the clearhouse service

```
cd clearinghouse
mvn spring-boot:run
```

2. Run the billing service

```
cd billing
mvn spring-boot:run
```

3. Setup python
   See client/README.md

Once python env is setup (and you've run the proto type generation script)

```
python submit_claims.py <path-to-claims> --rate 1
```

In antoher terminal

```
python poll_billing_service.py --interval 5
```
