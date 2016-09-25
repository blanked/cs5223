import java.util.HashMap;

public abstract class RoleManager {
	public RoleManager(GameManager gm) {
		gm_ = gm;
		cur_seq_num_ = 0;
		player_states_ = new PlayersStateMsg();
		history_ = new HashMap<Integer, Message>();
	}

	public void promote(RoleManager rm) {
		info_ = rm.getInfo();
		playground_ = rm.getPlayground();
		player_states_ = rm.getPlayerStates();
		cur_seq_num_ = rm.getCurSeqNum();
		history_ = rm.getHistory();
	}

	public InfoMsg getInfo() {
		return info_;
	}

	public int getCurSeqNum() {
		return cur_seq_num_;
	}

	public Playground getPlayground() {
		return playground_;
	}

	public PlayersStateMsg getPlayerStates() {
		return player_states_;
	}

	public HashMap<Integer, Message> getHistory() {
		return history_;
	}

	public abstract void handle(InfoMsg info);

	public abstract void handle(Player player, JoinMsg msg);

	public abstract void handle(Player player, PlayersStateMsg state);

	public abstract void handle(MazeStateMsg msg);

	public abstract void handle(Player player, MoveMsg msg);

	public abstract void onTrackerDown();

	public abstract void onDisconnected(Player player);

	public abstract void move(char direction);

	protected GameManager gm_;
	protected InfoMsg info_;

	protected Playground playground_;
	protected PlayersStateMsg player_states_;

	protected int cur_seq_num_;
	protected HashMap<Integer, Message> history_;
}

class PrimaryManager extends RoleManager {
	private static Logger logger = new Logger("PrimaryManager");

	public PrimaryManager(GameManager gm) {
		super(gm);
	}

	public void promote(PlayerManager pm) {
		super.promote(pm);
		self_ = new PlayerState();
		self_.host = gm_.getLocalHost();
		self_.listening_port = gm_.getListeningPort();
		self_.id = gm_.getPlayerId();

		playground_.initPlayer(self_);

		player_states_.addPlayer(self_);
		gm_.connectTracker();
		update(null);
	}

	public void promote(SecondaryManager sm) {
		super.promote(sm);

		TrackerPeerInfo primary = info_.getPeers().get(0);
		gm_.reportQuitPlayer(primary.host, primary.listening_port);

		player_states_.removePlayer(primary.host, primary.listening_port);
		for (PlayerState state : player_states_.getPlayersState()) {
			if (!info_.has(state.host, state.listening_port)) {
				player_states_.removePlayer(state.host, state.listening_port);
				logger.log("promote", String.format("quited player[%s]", state.id));
			}
		}
		for (PlayerState state : player_states_.getPlayersState()) {
			playground_.setPlayer(state.x, state.y, state);
			if (state.host.equals(gm_.getLocalHost()) && state.listening_port == gm_.getListeningPort())
				self_ = state;
		}

		update(null);
	}

	@Override
	public void onTrackerDown() {
		gm_.connectTracker();
	}

	@Override
	public void handle(InfoMsg info) {
		logger.log("handle", "InfoMsg");
		info_ = info;
		gm_.broadcast(info_);
	}

	@Override
	public void handle(Player player, PlayersStateMsg state) {
		logger.log("handle", "unexpected PlayersState");
	}

	@Override
	public void handle(MazeStateMsg msg) {
		logger.log("handle", "unexpected MazeStateMsg");
	}

	@Override
	public void handle(Player player, MoveMsg msg) {
		logger.log("handle",
				String.format("MoveMsg player[%s] direction[%s]", player.getState().id, msg.getDirection()));
		switch (msg.getDirection()) {
		case '1':
			playground_.moveWest(player.getState());
			break;
		case '2':
			playground_.moveSouth(player.getState());
			break;
		case '3':
			playground_.moveEast(player.getState());
			break;
		case '4':
			playground_.moveNorth(player.getState());
			break;
		case '0':
			break;
		case '9':
			gm_.kickPlayer(player);
			return;
		default:
			break;
		}
		player.getState().last_seq_num = msg.getSeqNum();

		update(player);
	}

	@Override
	public void onDisconnected(Player player) {
		player_states_.removePlayer(player.getState().host, player.getState().listening_port);

		if (player == secondary_) {
			secondary_ = null;
			logger.log("onDisconnected", "secondary server down");
			for (int i = 2; i < info_.getPeers().size(); ++i) {
				TrackerPeerInfo secondary = info_.getPeers().get(i);
				Player player0 = gm_.getPlayer(secondary.host, secondary.listening_port);
				if (player0 != null) {
					secondary_ = player0;
					logger.log("onDisconnected", String.format("nominate new secondary server host[%s] port[%s]",
							secondary.host, secondary.listening_port));
					break;
				}
			}
		}

		playground_.setPlayer(player.getState().x, player.getState().y, null);

		gm_.reportQuitPlayer(player.getState().host, player.getState().listening_port);
		update(null);
	}

	@Override
	public void handle(Player player, JoinMsg msg) {
		PlayerState state = new PlayerState(msg.getId(), msg.getHost(), msg.getListeningPort());
		player.setState(state);
		for (PlayerState p : player_states_.getPlayersState()) {
			if (p.host.equals(state.host) && p.listening_port == state.listening_port) {
				player.setState(p);
				logger.log("handle", String.format("JoinMsg update %s", p));
				break;
			}
		}

		state = player.getState();
		if (state.x == -1) {
			playground_.initPlayer(state);
			player_states_.addPlayer(state);
		}

		if (info_.getPeers().size() >= 2) {
			TrackerPeerInfo secondary = info_.getPeers().get(1);
			if (secondary.host.equals(state.host) && secondary.listening_port == state.listening_port) {
				secondary_ = player;
				logger.log("handle", "JoinMsg secondary server joined");
			} else {
				logger.log("handle", String.format("JoinMsg player joined id[%s]", state.id));
			}
		} else if (info_.getPeers().size() == 1) {
			secondary_ = player;
			logger.log("handle", "JoinMsg secondary server joined");
		}
		update(player);
	}

	@Override
	public void move(char direction) {
		switch (direction) {
		case '1':
			playground_.moveWest(self_);
			break;
		case '2':
			playground_.moveSouth(self_);
			break;
		case '3':
			playground_.moveEast(self_);
			break;
		case '4':
			playground_.moveNorth(self_);
			break;
		case '9':
			gm_.stop();
			break;
		default:
			break;
		}
		++self_.last_seq_num;
		++cur_seq_num_;
		update(null);
	}

	private void update(Player player) {
		player_states_.serialize();
		MazeStateMsg msg = new MazeStateMsg(playground_);
		msg.serialize();

		gm_.updateGUI(player_states_);
		gm_.updateGUI(msg);

		if (secondary_ != null) {
			secondary_.getConnection().write(player_states_);
			secondary_.getConnection().write(msg);
		}
		if (player != null && player != secondary_) {
			player.getConnection().write(player_states_);
			player.getConnection().write(msg);
		}
	}

	private PlayerState self_;
	private Player secondary_;
}

class PlayerManager extends RoleManager {
	private static Logger logger = new Logger("PlayerManager");

	public PlayerManager(GameManager gm) {
		super(gm);
	}

	@Override
	public void onTrackerDown() {
	}

	@Override
	public void handle(InfoMsg info) {
		logger.log("handle", "InfoMsg");

		info_ = info;
		logger.log("handle", String.format("  N[%s] K[%s]", info_.getN(), info_.getK()));
		for (TrackerPeerInfo peer : info_.getPeers()) {
			logger.log("handle", String.format("    peer host[%s] port[%s]", peer.host, peer.listening_port));
		}
		
		if (playground_ == null) {
			playground_ = new Playground(info_.getN(), info_.getK());
			gm_.startGUI(info_.getN());
		}
		
		if (player_states_ != null) {
			player_states_.consolidate(info_);
			gm_.updateGUI(player_states_);
		}

		if (info_.getPeers().isEmpty()) {
			logger.log("handle", "WRN: empty peer list");
		} else if (info_.getPeers().size() == 1) {
			gm_.promotePrimary(this);
		} else {
			TrackerPeerInfo secondary = info_.getPeers().get(1);
			if (gm_.getLocalHost().equals(secondary.host) && gm_.getListeningPort() == secondary.listening_port) {
				gm_.promoteSecondary(this);
			} else {
				if (!connectPrimary())
					gm_.stop();
			}
		}
	}

	@Override
	public void handle(Player player, PlayersStateMsg msg) {
		logger.log("handle", "PlayersStateMsg");
		player_states_ = msg;
		gm_.updateGUI(msg);
	}

	@Override
	public void handle(MazeStateMsg msg) {
		logger.log("handle", "MazeStateMsg");
		msg.setPlayground(playground_);
		msg.deserialize();
		gm_.updateGUI(msg);
	}

	@Override
	public void handle(Player player, MoveMsg msg) {
		logger.log("handle", "unexpected MoveMsg");
	}

	@Override
	public void onDisconnected(Player player) {
		player.stop();
		if (player == primary_) {
			player_states_.removePlayer(primary_.getState().host, primary_.getState().listening_port);
			primary_ = null;
			if (connectPrimary())
				return;
			gm_.stop();
		}
	}

	@Override
	public void handle(Player player, JoinMsg msg) {
		logger.log("handle", "unexpected JoinMsg");
	}

	@Override
	public void move(char direction) {
		if (primary_ != null) {
			MoveMsg msg = new MoveMsg(direction, ++cur_seq_num_);
			msg.serialize();
			primary_.getConnection().write(msg);
			history_.put(cur_seq_num_, msg);
		}
	}

	public boolean connectPrimary() {
		if (primary_ != null)
			return true;

		logger.log("connectPrimary", "");
		for (TrackerPeerInfo peer : info_.getPeers()) {
			if (peer.host.equals(gm_.getLocalHost()) && peer.listening_port == gm_.getListeningPort()) {
				gm_.promotePrimary(this);
				return true;
			}
			primary_ = gm_.connect(peer.host, peer.listening_port);
			if (primary_ == null) {
				gm_.reportQuitPlayer(peer.host, peer.listening_port);
				if (player_states_ != null)
					player_states_.removePlayer(peer.host, peer.listening_port);
			} else {
				gm_.disconnectTracker();

				JoinMsg msg = new JoinMsg(gm_.getPlayerId(), gm_.getLocalHost(), gm_.getListeningPort(), cur_seq_num_);
				history_.put(cur_seq_num_, new JoinMsg(msg));
				msg.serialize();
				primary_.getConnection().write(msg);
				return true;
			}
		}

		return false;
	}

	protected Player primary_;
}

class SecondaryManager extends PlayerManager {
	private static Logger logger = new Logger("SecondaryManager");

	public SecondaryManager(GameManager gm) {
		super(gm);
	}

	public void promote(PlayerManager pm) {
		super.promote(pm);
		primary_ = pm.primary_;

		gm_.disconnectTracker();
		connectPrimary();
	}

	public void handle(InfoMsg info) {
		logger.log("handle", "InfoMsg");

		info_ = info;
		logger.log("handle", String.format("  N[%s] K[%s]", info_.getN(), info_.getK()));
		for (TrackerPeerInfo peer : info_.getPeers()) {
			logger.log("handle", String.format("    peer host[%s] port[%s]", peer.host, peer.listening_port));
		}

		if (player_states_ != null) {
			player_states_.consolidate(info_);
		}
	}

	public void handle(Player player, JoinMsg msg) {
		logger.log("handle", "JoinMsg");
		// TODO(x) should handle the join in case other players gets
		// disconnected from primary first
	}

	public void onDisconnected(Player player) {
		player.stop();
		if (player == primary_) {
			gm_.promotePrimary(this);
			primary_ = null;
		}
	}
}
