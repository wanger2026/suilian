package easytier

import "context"

type packetReceiver interface {
    ReceivePacket(context.Context) ([]byte, error)
}

// DrainUnhandledPackets is required when using only Dial/ListenPacket instead
// of a raw TUN. Late TCP packets and unmatched traffic still enter the host's
// bounded raw packet sink. Leaving that sink unread backpressures the peer's
// entire receive loop, including liveness and otherwise healthy connections.
// These packets have no consumer in this outbound adapter and must not be
// forwarded to Android's TUN or another network. ReceivePacket blocks when idle
// and returns on instance shutdown; this worker owns no timer or polling loop.
func DrainUnhandledPackets(ctx context.Context, receiver packetReceiver) {
    for {
        if _, err := receiver.ReceivePacket(ctx); err != nil { return }
    }
}
