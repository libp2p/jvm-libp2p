# Interop Tests

For more info: https://github.com/libp2p/test-plans/tree/master/transport-interop#readme

## Requirements

To run the interop test framework locally, you need:

- Docker
- node, nvm and ts-node

## Running it locally

The first thing to be able to run the test locally is build the images of each livp2p implementation
being tested. You need to run the following steps for each one of the implementations that you are
planning to run:

1. Checkout the project https://github.com/libp2p.
2. Navigate to test-plans/impl/<IMPL>/<VERSION>; where <IMPL> is the implementation that you want to
   build (e.g. `jvm`) and <VERSION> is what version you want (e.g. `v1.2`).
3. Once in the specific version folder run `make` to build the image. This will create a
   `image.json` file with the hash of the Docker image built.

Once you have the images that you want, navigate back to the `transport-interop` folder and run:

```
npm test --name-filter=jvm-v1.2
```

The parameter `--name-filter` can be used to limit the pairs that are going to be executed.
In the previous example, only pairs with `jvm-1.2` are going to run.
Similarly, `--name-ignore` can be used to remove pairs.

Here is the output of a sample run:

```
npm test --name-filter=jvm-v1.2 --name-ignore=<OMMITED> --verbose=true

> @libp2p/transport-interop@0.0.1 test
> ts-node src/compose-stdout-helper.ts && ts-node testplans.ts

Checking jvm-v1.2 x jvm-v1.2 (tcp, tls, mplex)...ACCEPTED (filter match: '*')
Running 1 tests
Running test spec: jvm-v1.2 x jvm-v1.2 (tcp, tls, mplex)
Finished: jvm-v1.2 x jvm-v1.2 (tcp, tls, mplex) { handshakePlusOneRTTMillis: 380, pingRTTMilllis: 2 }
0 failures []
Run complete
```