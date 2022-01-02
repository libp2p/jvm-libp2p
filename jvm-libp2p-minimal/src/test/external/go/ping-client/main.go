package main

import (
	"context"
	"fmt"
	"github.com/libp2p/go-libp2p"
	peerstore "github.com/libp2p/go-libp2p-peerstore"
	"github.com/libp2p/go-libp2p/p2p/protocol/ping"
	multiaddr "github.com/multiformats/go-multiaddr"
	"os"
        noise "github.com/libp2p/go-libp2p-noise"
)

func main() {
	// create a background context (i.e. one that never cancels)
	ctx := context.Background()

	// build a libp2p node
	options := makeOptions()
	node, err := libp2p.New(ctx, options...)
	if err != nil {
		panic(err)
	}
	
	// configure our own ping protocol
	pingService := &ping.PingService{Host: node}
	node.SetStreamHandler(ping.ID, pingService.PingHandler)

	// if a remote peer has been passed on the command line, connect to it
	// and send it 5 ping messages, otherwise wait for a signal to stop
	if len(os.Args) > 1 {
		addr, err := multiaddr.NewMultiaddr(os.Args[len(os.Args)-1])
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
		for i := 1; i <= 5; i++ {
			fmt.Printf("Ping %v", i)
			pingResp := <-ch
			fmt.Printf(" in %v\n", pingResp.RTT)
		}
	}

	// shut the node down
	if err := node.Close(); err != nil {
		// panic(err)
	}
}

func makeOptions() []libp2p.Option {
	options := []libp2p.Option{
		libp2p.Ping(false),
	}
	if wantPlaintext() {
		options = append(options, libp2p.NoSecurity)
	}
	if wantNoise() {
        	options = append(options, libp2p.Security(noise.ID, noise.New))
	}
	return options
}

func wantPlaintext() bool {
	args := os.Args[1:]
	return len(args) != 0 && args[0] == "--plaintext"
}

func wantNoise() bool {
	args := os.Args[1:]
	return len(args) != 0 && args[0] == "--noise"
}
