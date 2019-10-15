const createPeerNode = require('./peer-node')

createPeerNode()
  .then(server => {
    const address = server.peerInfo.multiaddrs.toArray()[0]
    console.log(address.toString())
  })
