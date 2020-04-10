package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/libp2p/go-libp2p"
	peerstore "github.com/libp2p/go-libp2p-peerstore"
        noise "github.com/libp2p/go-libp2p-noise"
)

func main() {
	// create a background context (i.e. one that never cancels)
	ctx := context.Background()

	// start a libp2p node that listens on a random local TCP port
	options := makeOptions()
	node, err := libp2p.New(ctx, options...)
	if err != nil {
		panic(err)
	}

	// print the node's PeerInfo in multiaddr format
	peerInfo := &peerstore.PeerInfo{
		ID:    node.ID(),
		Addrs: node.Addrs(),
	}
	addrs, err := peerstore.InfoToP2pAddrs(peerInfo)
	if err != nil {
		panic(err)
	}
	fmt.Println(addrs[0])

	// wait for a SIGINT or SIGTERM signal
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	fmt.Println("Received signal, shutting down...")

	// shut the node down
	if err := node.Close(); err != nil {
		panic(err)
	}
}

func makeOptions() []libp2p.Option {
    listenAddr := "/ip4/127.0.0.1/tcp/0"
    if (wantWebSocket()) {
        listenAddr += "/ws"
    }

	options := []libp2p.Option{
		libp2p.ListenAddrStrings(listenAddr),
		libp2p.Ping(true),
	}
	if wantPlaintext() {
		options = append(options, libp2p.NoSecurity)
	}
        options = append(options, libp2p.Security(noise.ID, noise.New))
	return options
}

func wantPlaintext() bool {
	return hasArgument("--plaintext")
}

func wantWebSocket() bool {
	return hasArgument("--websocket")
}

func hasArgument(wanted string) bool {
    for _, arg := range os.Args[1:] {
        if arg == wanted {
            return true
        }
    }
    return false
}