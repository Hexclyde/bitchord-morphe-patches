package android.net;

public final class Network {
    private final int id;

    public Network(int id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof Network && ((Network) value).id == id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
