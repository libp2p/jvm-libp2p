extern crate libp2p;
extern crate futures;

use futures::{prelude::*, future};
use libp2p::identity;
use libp2p::PeerId;
use libp2p::ping::{ Ping, PingConfig };
use libp2p::Swarm;
use libp2p::secio;
use libp2p::mplex;
use libp2p::tcp;
use libp2p::Transport;
use libp2p::core;
use libp2p::plaintext;
use std::time::Duration;

fn main() {
    // Load the PeerId.
    let mut bytes = std::fs::read("../test-rsa-private-key.pk8").unwrap();
    let id_keys = identity::Keypair::rsa_from_pkcs8(&mut bytes).unwrap();
    let peer_id = PeerId::from(id_keys.public());

    // Create a transport.
    let transport = tcp::TcpConfig::new()
        .upgrade(core::upgrade::Version::V1)
        .authenticate(plaintext::PlainText2Config {
            local_public_key: id_keys.public()
        })
        .multiplex(mplex::MplexConfig::new())
        .timeout(Duration::from_secs(20));

    // Create a ping network behaviour.
    let behaviour = Ping::new(PingConfig::new().with_keep_alive(true));

    // Create a Swarm that establishes connections through the given transport
    // and applies the ping behaviour on each connection.
    let mut swarm = Swarm::new(transport, behaviour, peer_id);

    // Tell the swarm to listen on all interfaces and a random, OS-assigned port.
    Swarm::listen_on(&mut swarm, "/ip4/0.0.0.0/tcp/0".parse().unwrap()).unwrap();

    // Use tokio to drive the `Swarm`.
    let mut listening = false;
    tokio::run(future::poll_fn(move || -> Result<_, ()> {
        loop {
            match swarm.poll().expect("Error while polling swarm") {
                Async::Ready(Some(e)) => println!("{:?}", e),
                Async::Ready(None) | Async::NotReady => {
                    if !listening {
                        if let Some(a) = Swarm::listeners(&swarm).next() {
                            println!("{}/ipfs/{}", a, Swarm::local_peer_id(&swarm));
                            listening = true;
                        }
                    }
                    return Ok(Async::NotReady)
                }
            }
        }
    }));
}
