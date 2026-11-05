package com.music.bitchord.data.listentogether;

/** The party state BitChord 1.9 exposes: a code that is empty outside a party. */
public final class ListenTogether {
    public static final ListenTogether INSTANCE = new ListenTogether();
    public static volatile String joinedCode = "";
    // BitChord declares the server connection flow before the party state; it has no code.
    public static final ConnectionFlow _serverConnectionState = new ConnectionFlow();
    public static final Flow _state = new Flow();
    public static final Flow state = _state;

    private ListenTogether() {}

    public static final class ConnectionFlow {
        public Connection getValue() {
            return new Connection();
        }
    }

    public static final class Connection {
        public final boolean reachable = true;
    }

    public static final class Flow {
        public State getValue() {
            return new State(joinedCode);
        }
    }

    public static final class State {
        public final String code;

        State(String code) {
            this.code = code;
        }
    }
}
