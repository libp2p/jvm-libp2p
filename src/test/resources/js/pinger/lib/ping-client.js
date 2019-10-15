const multiaddr = require('multiaddr')
const PeerInfo = require('peer-info')
const PeerId = require('peer-id')
const Ping = require('libp2p/src/ping')
const createPeerNode = require('./peer-node')

function ping (pingNode, remotePeerInfo) {
  return new Promise((resolve, reject) => {
    const p = new Ping(pingNode._switch, remotePeerInfo)
    p.on('ping', time => {
      p.stop()
      resolve(time)
    })
    p.on('error', reject)
    p.start()
  })
}

function createRemotePeerInfo(address) {
  const remoteAddr = multiaddr(address)
  const peerId = PeerId.createFromB58String(remoteAddr.getPeerId())
  const remotePeerInfo = new PeerInfo(peerId)
  remotePeerInfo.multiaddrs.add(remoteAddr)
  return remotePeerInfo
}

async function pingRemotePeer(address) {
  const pingNode = await createPeerNode()
  const remotePeerInfo = createRemotePeerInfo(address)

  console.log(`pinging remote peer at ${address}`)
  for (let i = 0; i != 5; ++i) {
    const time = await ping(pingNode, remotePeerInfo)
    console.log(`ping ${i+1} in ${time}ms`)
  }

  pingNode.stop()
}

if (process.argv.length >= 3)
  pingRemotePeer(process.argv[2])
