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
TEST_FORCE_THREAD_POOL=1 clojure -X:test
clojure -X:test-cljs
```

Local install:

```shell
clojure -T:build jar && clojure -T:build install
```
