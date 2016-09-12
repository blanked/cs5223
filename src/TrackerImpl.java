import java.nio.ByteBuffer;
import java.util.ArrayList;

public class TrackerImpl implements ServerSocketListenerI, ConnectionListenerI {
	public TrackerImpl(int listening_port, int N, int K) {
		info_ = new InfoMsg(N, K);
		info_.serialize();
		server_ = new ConnectionManager(listening_port, this);
		connections_ = new ArrayList<Connection>();
	}

	public boolean start() {
		return server_.start();
	}

	public void stop() {
		server_.stop();
	}

	@Override
	public void onAccepted(Connection connection) {
		connections_.add(connection);
		connection.set_listener(this);
		System.out.format("TrackerImpl::onAccepted() Client accepted count[%s]\n", connections_.size());
		connection.write(info_);
	}

	@Override
	public void onDisconnected(Connection connection) {
		server_.close(connection);
		connections_.remove(connection);
		System.out.format("TrackerImpl::OnDisconnected() Client disconnected count[%s]\n", connections_.size());
		if (connections_.isEmpty()) {
			info_.clearPeers();
			System.out.println("TrackerImpl::OnDisconnected() clear peers");
		}
	}

	@Override
	public void onData(Connection connection, ByteBuffer buffer) {
		MsgType msg_type = MsgType.values()[buffer.getInt()];
		switch (msg_type) {
		case kInfo:
			InfoMsg msg = new InfoMsg(buffer);
			if (msg.deserialize()) {
				info_ = msg;
				info_.serialize();
				System.out.format("TrackerImpl::OnMessage() kInfo remote[%s]\n", connection.getRemoteAddress());
				for (TrackerPeerInfo peer : info_.getPeers()) {
					System.out.format("    peer host[%s] port[%s]\n", peer.host, peer.listening_port);
				}
			}
			break;
		default:
			break;
		}
	}

	InfoMsg info_;
	ConnectionManager server_;
	ArrayList<Connection> connections_;
}
