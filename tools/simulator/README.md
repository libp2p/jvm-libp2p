# jvm-libp2p Gossip simulator

## Description

This is Gossip simulator which may simulate networks as large as 10000 peers

The simulator is _deterministic_. That is: 
- yields 100% identical results across different runs with the same configuration and the same random seed
- a simulation may forward current time as needed

## Configuring simulation

All simulations are configured programmatically inside the simulation code. 

You could make a copy of an existing simulation (from `io.libp2p.simulate.main` package) or create a new one 
and change simulation configuration right in the Kotlin class 

## Running simulation with gradle 

Any main function could be run from CLI with gradle using the following syntax: 
```shell
> gradle :tools:simulator:run -PmainClass=<your.main.Class> [--args="you args"]
```

For example to run the sample simulation use the command below: 
```shell
> gradle :tools:simulator:run -PmainClass=io.libp2p.simulate.main.SimpleSimulationKt
```

## License

Dual-licensed under MIT and ASLv2, by way of the [Permissive License
Stack](https://protocol.ai/blog/announcing-the-permissive-license-stack/).
