extern crate libp2p;
extern crate futures;
extern crate tokio;
extern crate async_std;

use async_std::task;
use futures::{prelude::*, future};
use libp2p::identity;
use libp2p::PeerId;
use libp2p::ping::{ Ping, PingConfig, PingEvent };
use libp2p::Swarm;
use libp2p::secio;
use libp2p::mplex;
use libp2p::tcp;
use libp2p::Transport;
use libp2p::identify::{ Identify, IdentifyEvent };
use libp2p::core;
use libp2p::plaintext;
use libp2p::NetworkBehaviour;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use libp2p::swarm::NetworkBehaviourEventProcess;
use std::{error::Error, task::{Context, Poll}};
use std::{thread, time};
use libp2p::noise::{Keypair, X25519, NoiseConfig};



fn main() {
    // Load the PeerId.
    let mut bytes = std::fs::read("../test-rsa-private-key.pk8").unwrap();
    let id_keys = identity::Keypair::rsa_from_pkcs8(&mut bytes).unwrap();
    let peer_id = PeerId::from(id_keys.public());
    let dh_keys = Keypair::<X25519>::new().into_authentic(&id_keys).unwrap();

//    let id_keys = identity::Keypair::generate_ed25519();
//    let peer_id = PeerId::from(id_keys.public());


    // Create a transport.
    let transport = tcp::TcpConfig::new()
        .upgrade(core::upgrade::Version::V1)
        .authenticate(
            //plaintext::PlainText2Config { local_public_key: id_keys.public() }
            //secio::SecioConfig::new(id_keys.clone()))
            NoiseConfig::xx(dh_keys).into_authenticated()
        )
        .multiplex(mplex::MplexConfig::new())
        .map(|(peer, muxer), _| (peer, core::muxing::StreamMuxerBox::new(muxer)))
        .timeout(Duration::from_secs(20));

    let behaviour = Ping::new(PingConfig::new().with_keep_alive(true));
    let mut swarm = Swarm::new(transport, behaviour, peer_id);

    // Tell the swarm to listen on all interfaces and a random, OS-assigned port.
    Swarm::listen_on(&mut swarm, "/ip4/127.0.0.1/tcp/12345".parse().unwrap()).unwrap();


   let mut listening = false;
    async_std::task::block_on(futures::future::poll_fn( move |cx: &mut Context| {
        loop {
            match swarm.poll_next_unpin(cx) {
                Poll::Ready(Some(event)) => println!("{:?}", event),
                Poll::Ready(None) => return Poll::Ready(()),
                Poll::Pending => {
                    if !listening {
                        for addr in Swarm::listeners(&swarm) {
                            println!("Listening on {}", addr);
                            listening = true;
                        }
                    }
                    return Poll::Pending
                }
            }
        }
    }));
}
