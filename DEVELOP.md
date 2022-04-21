# Development

Deploying:

```shell
clojure -T:build jar && 
CLOJARS_USERNAME=username \
  CLOJARS_PASSWORD=token \
  clojure -T:build deploy
```

Testing:

```shell
clojure -X:test
clojure -X:test-cljs
```

Local install:

```shell
clojure -T:build jar && clojure -T:build install
```
