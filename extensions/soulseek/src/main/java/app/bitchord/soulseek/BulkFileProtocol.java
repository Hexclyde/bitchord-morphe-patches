package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** One bounded, token-authenticated request per connection. No path comes from the peer. */
public final class BulkFileProtocol {
    private static final int MAGIC = 0x42434631, RECEIPT = 0x42434131, MAX_HEADER = 8192;

    private BulkFileProtocol() {}

    public interface Provider {
        byte[] read(String fileId, String token, int chunk) throws IOException;
    }

    public static byte[] request(
            InputStream in, OutputStream out, String fileId, String token, int chunk)
            throws IOException {
        try {
            JSONObject request =
                    new JSONObject().put("file", fileId).put("token", token).put("chunk", chunk);
            writeHeader(out, request);
            JSONObject reply = readHeader(in);
            if (!reply.optBoolean("ok", false))
                throw new IOException("Peer refused the file request.");
            int length = reply.getInt("size");
            if (length <= 0 || length > BulkManifest.BLOCK_SIZE)
                throw new IOException("Peer sent an invalid block size.");
            byte[] data = new byte[length];
            new DataInputStream(in).readFully(data);
            DataOutputStream acknowledgement = new DataOutputStream(out);
            acknowledgement.writeInt(RECEIPT);
            acknowledgement.flush();
            if (new DataInputStream(in).readInt() != RECEIPT)
                throw new IOException("Peer did not complete the file response.");
            return data;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid file response.", error);
        }
    }

    public static void serve(InputStream in, OutputStream out, Provider provider)
            throws IOException {
        try {
            JSONObject request = readHeader(in);
            String id = request.getString("file"), token = request.getString("token");
            int chunk = request.getInt("chunk");
            if (!id.matches("[0-9a-f]{32}")
                    || !BulkManifest.hex64(token)
                    || chunk < -1
                    || chunk >= 1024) throw new IOException("Invalid file request.");
            byte[] data = provider.read(id, token, chunk);
            if (data == null || data.length == 0 || data.length > BulkManifest.BLOCK_SIZE)
                throw new IOException("File is unavailable.");
            writeHeader(out, new JSONObject().put("ok", true).put("size", data.length));
            out.write(data);
            out.flush();
            if (new DataInputStream(in).readInt() != RECEIPT)
                throw new IOException("Missing file receipt.");
            new DataOutputStream(out).writeInt(RECEIPT);
            out.flush();
        } catch (Exception error) {
            // A single generic denial reveals neither publication IDs nor the supplied token.
            try {
                writeHeader(out, new JSONObject().put("ok", false));
            } catch (Exception ignored) {
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Invalid file request.", error);
        }
    }

    private static JSONObject readHeader(InputStream in) throws Exception {
        DataInputStream data = new DataInputStream(in);
        if (data.readInt() != MAGIC) throw new IOException("Unsupported file protocol.");
        int count = data.readInt();
        if (count <= 0 || count > MAX_HEADER) throw new IOException("Invalid file header size.");
        byte[] bytes = new byte[count];
        data.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void writeHeader(OutputStream out, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HEADER) throw new IOException("File header is too large.");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(bytes.length);
        data.write(bytes);
        data.flush();
    }
}
