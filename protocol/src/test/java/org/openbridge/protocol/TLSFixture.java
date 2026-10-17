package org.openbridge.protocol;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import javax.net.ssl.*;

/** CI-only loopback server for the real Swift client. Never packaged in either app. */
public final class TLSFixture {
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, "test-only".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-only".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("fixture").getEncoded());
        String fingerprint = HexFormat.of().formatHex(digest);
        MemoryStore store = new MemoryStore();
        // Approves every pairing request, as if the user tapped Pair after comparing the codes.
        Session session = new Session(store, ProtocolTest.SECRET, digest, code -> true);
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            System.out.println(server.getLocalPort() + " " + fingerprint); System.out.flush();
            while (true) {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(5000); socket.startHandshake();
                    session.serve(socket.getInputStream(), socket.getOutputStream());
                } catch (IOException expectedForRejectedClients) { /* Continue to next test. */ }
            }
        }
    }
}
