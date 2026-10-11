package vn.edu.fs.server;

import vn.edu.fs.common.AuthService;
import vn.edu.fs.common.Frame;
import vn.edu.fs.common.FrameIO;

import java.io.*;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Vong doi mot ket noi: doc khung -> dispatcher -> dong sach tai nguyen. */
final class ClientHandler implements Runnable {
    private final Socket socket;
    private final CommandDispatcher dispatcher;
    private final AuthService auth;

    ClientHandler(Socket socket, CommandDispatcher dispatcher, AuthService auth) {
        this.socket = socket; this.dispatcher = dispatcher; this.auth = auth;
    }

    @Override public void run() {
        Connection c = null;
        try (socket) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            c = new Connection(String.valueOf(socket.getRemoteSocketAddress()), in, out);
            while (true) {
                Frame f = FrameIO.read(in);
                if (f == null) break;                                  // client dong ket noi
                if (f.type() != Frame.CONTROL) {
                    FrameIO.writeControl(out, "ERR\t400\tKhung phai la CONTROL");
                    break;
                }
                if (!dispatcher.dispatch(c, f.text())) break;
            }
        } catch (SocketTimeoutException e) {
            // 60 giay khong hoat dong -> cat ket noi treo
        } catch (ProtocolException e) {
            tryErr(c, "ERR\t400\tGiao thuc khong hop le");
        } catch (IOException e) {
            // ket noi hong/ngat giua chung: chi dong
        } catch (RuntimeException e) {
            tryErr(c, "ERR\t500\tLoi may chu");
        } finally {
            if (c != null && c.session != null) auth.logout(c.session.token());
        }
    }

    private void tryErr(Connection c, String msg) {
        if (c == null) return;
        try { FrameIO.writeControl(c.out, msg); } catch (IOException ignored) {}
    }
}
