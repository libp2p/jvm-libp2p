const multiaddr = require('multiaddr')
const PeerInfo = require('peer-info')
const PeerId = require('peer-id')
const Libp2p = require('libp2p')
const TCP = require('libp2p-tcp')
const Multiplex = require('libp2p-mplex')
const SECIO = require('libp2p-secio')

const defaultsDeep = require('@nodeutils/defaults-deep')

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

class PingNode extends Libp2p {
  constructor (opts) {
    super(defaultsDeep(opts, DEFAULT_OPTS))
  }

}

function createPingServer(callback) {
  PeerInfo.create((err, peerInfo) => {
    if (err) return callback(err)

    const listenAddress = multiaddr('/ip4/127.0.0.1/tcp/0')
    peerInfo.multiaddrs.add(listenAddress)

    const server = new Libp2p(defaultsDeep(
        {peerInfo},
        DEFAULT_OPTS
    ))

    server.on('error', err => {
      console.error('libp2p error: ', err)
      throw err
    })

    callback(null, server)
  })
}

createPingServer((err, server) => {
  if (err) throw err

  server.start(err => {
    if (err) throw err

    const address = server.peerInfo.multiaddrs.toArray()[0]
    console.log(address.toString())
  })
})
