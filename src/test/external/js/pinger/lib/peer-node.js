const multiaddr = require('multiaddr')
const PeerInfo = require('peer-info')
const Libp2p = require('libp2p')
const TCP = require('libp2p-tcp')
const Multiplex = require('libp2p-mplex')
const SECIO = require('libp2p-secio')

const defaultsDeep = require('@nodeutils/defaults-deep')

const promisify = require('util').promisify

const DEFAULT_OPTS = {
  modules: {
    transport: [
      TCP
    ],
    connEncryption: [
      SECIO
    ],
    streamMuxer: [
      Multiplex
    ]
  }
}

const createPeerInfo = promisify(PeerInfo.create)

async function createPeerNode() {
  const peerInfo = await createPeerInfo();
  const listenAddress = multiaddr('/ip4/127.0.0.1/tcp/0')
  peerInfo.multiaddrs.add(listenAddress)

  const node = new Libp2p(defaultsDeep(
    {peerInfo},
    DEFAULT_OPTS
  ))

  node.on('error', err => {
    console.error('libp2p error: ', err)
    throw err
  })

  return new Promise((resolve, reject) => {
    node.start(err => {
      if (err)
        reject(err)
      else
        resolve(node)
    })
  })
}

module.exports = createPeerNode
