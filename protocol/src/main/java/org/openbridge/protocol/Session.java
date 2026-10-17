package org.openbridge.protocol;

import java.io.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/** Blocking streaming handler, independent of Android and TLS implementation. */
public final class Session {
    private final Store store;
    private final byte[] secret;
    private final byte[] fingerprint;
    private final Pairing.Approver approver;
    private final SecureRandom random = new SecureRandom();

    /** A session that only accepts clients that already hold the secret. */
    public Session(Store store, byte[] secret) { this(store, secret, null, null); }

    /**
     * A session that can also pair new clients (OBP1): `fingerprint` is the SHA-256 of this server's TLS
     * certificate, and `approver` shows the 6-digit code to the user.
     */
    public Session(Store store, byte[] secret, byte[] fingerprint, Pairing.Approver approver) {
        this.store = store; this.secret = secret.clone(); this.approver = approver;
        this.fingerprint = fingerprint == null ? null : fingerprint.clone();
    }

    public void serve(InputStream input, OutputStream output) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        boolean responseStarted = false;
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (Arrays.equals(magic, Pairing.MAGIC)) {
                if (approver == null || fingerprint == null) throw new IOException("Pairing is not available");
                responseStarted = true;   // pairing reports its own errors after the exchange begins
                Pairing.serve(in, out, fingerprint, secret, approver, random);
                return;
            }
            Wire.authenticate(in, magic, secret);
            int operation = in.readUnsignedByte();
            String id = Wire.readString(in, 128);
            switch (operation) {
                case 1: {
                    List<Store.Entry> entries = store.list(id);
                    if (entries.size() > Wire.MAX_ENTRIES) throw new IOException("Folder has too many entries");
                    out.writeByte(0); responseStarted = true;
                    out.writeInt(entries.size());
                    for (Store.Entry entry : entries) {
                        Wire.writeString(out, entry.id);
                        Wire.writeString(out, entry.name);
                        out.writeBoolean(entry.directory);
                        out.writeLong(entry.size);
                    }
                    break;
                }
                case 2: {
                    try (Store.Read file = store.open(id)) {
                        long size = file.size();
                        if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                        out.writeByte(0); responseStarted = true;
                        out.writeLong(size);
                        Wire.copy(file.stream(), out, size);
                    }
                    break;
                }
                case 3: {
                    String name = Wire.readString(in, 255);
                    Wire.validateName(name);
                    long size = in.readLong();
                    if (size < 0 || size > Wire.MAX_FILE) throw new IOException("Unsupported file size");
                    try (Store.Upload upload = store.create(id, name)) {
                        // Ready acknowledgement lets clients report collisions before sending data.
                        out.writeByte(0); out.flush(); responseStarted = true;
                        try (OutputStream destination = upload.stream()) {
                            Wire.copy(in, destination, size);
                        }
                        // Commit failures are sent as the second status, after the file body.
                        try {
                            upload.commit();
                            out.writeByte(0);
                        } catch (IOException e) { error(out, e); }
                    }
                    break;
                }
                default: throw new IOException("Unsupported operation");
            }
        } catch (IOException | SecurityException e) {
            if (responseStarted) throw new IOException("Transfer interrupted", e);
            error(out, e);
        } finally { out.flush(); }
    }

    private static void error(DataOutputStream out, Exception error) throws IOException {
        out.writeByte(1);
        // Never disclose filesystem/provider paths or other exception details on the wire.
        String message = error instanceof SecurityException ? "Storage permission denied" : error.getMessage();
        if (message == null || message.length() > 120) message = "Operation failed";
        Wire.writeString(out, message);
    }
}
