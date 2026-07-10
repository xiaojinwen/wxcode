package fi.iki.elonen.util;

import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/* JADX INFO: loaded from: classes2.dex */
public class ServerRunner {
    private static final Logger LOG = Logger.getLogger(ServerRunner.class.getName());

    public static void executeInstance(NanoHTTPD server) {
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
            System.exit(-1);
        }
        System.out.println("Server started, Hit Enter to stop.\n");
        try {
            System.in.read();
        } catch (Throwable th) {
        }
        server.stop();
        System.out.println("Server stopped.\n");
    }

    public static <T extends NanoHTTPD> void run(Class<T> serverClass) {
        try {
            executeInstance(serverClass.newInstance());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Cound nor create server", (Throwable) e);
        }
    }
}
