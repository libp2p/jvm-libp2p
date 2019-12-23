extern crate libp2p;
extern crate futures;

use libp2p::identity;
use libp2p::PeerId;
use libp2p::ping::{ Ping, PingConfig };
use libp2p::Swarm;
use libp2p::mplex;
use libp2p::tcp;
use libp2p::Transport;
use libp2p::core;
use libp2p::plaintext;
use std::time::Duration;
use std::env;

fn main() {
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
    //
    // For illustrative purposes, the ping protocol is configured to
    // keep the connection alive, so a continuous sequence of pings
    // can be observed.
    let behaviour = Ping::new(PingConfig::new());

    // Create a Swarm that establishes connections through the given transport
    // and applies the ping behaviour on each connection.
    let mut swarm = Swarm::new(transport, behaviour, peer_id);

    // Dial the peer identified by the multi-address given
    let remote_addr = ping_address();
    for _ in 0..5 {
        let remote = remote_addr.parse().unwrap();
        match Swarm::dial_addr(&mut swarm, remote) {
            Ok(()) => println!("Dialed {:?}", remote_addr),
            Err(e) => println!("Dialing {:?} failed with: {:?}", remote_addr, e)
        }
    }
}

fn ping_address() -> String {
    let a = env::args().nth(1).unwrap();
    let mut chunks = a.split("/").collect::<Vec<_>>();
    if chunks.len() > 5 {
        chunks.pop();
        chunks.pop();
    }
    return chunks.join("/")
}