package vn.edu.fs.server;

import vn.edu.fs.common.Session;

import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Trang thai mot ket noi client. */
final class Connection {
    final String remote;
    final DataInputStream in;
    final DataOutputStream out;
    Session session;      // null = chua dang nhap
    int failedLogins;

    Connection(String remote, DataInputStream in, DataOutputStream out) {
        this.remote = remote; this.in = in; this.out = out;
    }
    String user() { return session == null ? "-" : session.userId(); }
}
