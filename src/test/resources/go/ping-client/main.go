package main

import (
	"context"
	"os"

	"github.com/libp2p/go-libp2p"
	peerstore "github.com/libp2p/go-libp2p-peerstore"
	"github.com/libp2p/go-libp2p/p2p/protocol/ping"
	multiaddr "github.com/multiformats/go-multiaddr"
)

func main() {
	// create a background context (i.e. one that never cancels)
	ctx := context.Background()

	// build a libp2p node
	node, err := libp2p.New(ctx,
		libp2p.Ping(false),
	)
	if err != nil {
		panic(err)
	}

	// configure our own ping protocol
	pingService := &ping.PingService{Host: node}
	node.SetStreamHandler(ping.ID, pingService.PingHandler)

	// if a remote peer has been passed on the command line, connect to it
	// and send it 5 ping messages, otherwise wait for a signal to stop
	if len(os.Args) > 1 {
		addr, err := multiaddr.NewMultiaddr(os.Args[1])
		if err != nil {
			panic(err)
		}
		peer, err := peerstore.InfoFromP2pAddr(addr)
		if err != nil {
			panic(err)
		}
		if err := node.Connect(ctx, *peer); err != nil {
			panic(err)
		}
		ch := pingService.Ping(ctx, peer.ID)
		for i := 1; i < 5; i++ {
			_ = <-ch // first call here sends two pings
		}
	}

	// shut the node down
	if err := node.Close(); err != nil {
		panic(err)
	}
}
