const multiaddr = require('multiaddr')
const PeerInfo = require('peer-info')
const PeerId = require('peer-id')

const PingNode = require('./ping')

function createPingServer(callback) {
  PeerInfo.create((err, peerInfo) => {
    if (err) return callback(err)

    const listenAddress = multiaddr('/ip4/127.0.0.1/tcp/40000')
    peerInfo.multiaddrs.add(listenAddress)

    const peer = new PingNode({peerInfo})

    peer.on('error', err => {
      console.error('libp2p error: ', err)
      throw err
    })

    callback(null, peer)
  })
}

createPingServer((err, peer) => {
  if (err) throw err

  peer.start(err => {
    if (err) throw err

    const addresses = peer.peerInfo.multiaddrs.toArray()
    console.log('Ping server started.\n  Listening on addresses:')
    addresses.forEach(addr => console.log(`    ${addr}`))
  })
})
