package com.tvremote;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Cliente ADB minimo por TCP (puerto 5555). Solo sirve si el TV no exige autorizacion.
public class Adb {
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    Socket sock;
    DataInputStream in;
    OutputStream out;
    int nextId = 1;

    static class Msg { int cmd, arg0, arg1; byte[] data; }

    synchronized boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    synchronized void connect(String host) throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, 5555), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(5000);
        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        send(A_CNXN, 0x01000000, 4096, "host::\0".getBytes("UTF-8"));
        for (int i = 0; i < 5; i++) {
            Msg m = read();
            if (m.cmd == A_CNXN) return;
            if (m.cmd == A_AUTH) {
                close();
                throw new IOException("El TV pide autorizar la conexion");
            }
        }
        close();
        throw new IOException("Respuesta inesperada del TV");
    }

    synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException e) { }
        sock = null;
        in = null;
        out = null;
    }

    synchronized void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        out.write(b.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > (1 << 20)) throw new IOException("Mensaje invalido");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    synchronized String shell(String cmd) throws IOException {
        if (sock == null) throw new IOException("Sin conexion");
        int id = nextId++;
        send(A_OPEN, id, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        int remote = 0;
        StringBuilder sb = new StringBuilder();
        while (true) {
            Msg m = read();
            if (m.arg1 != id) continue;
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                sb.append(new String(m.data, "UTF-8"));
                send(A_OKAY, id, remote, null);
            } else if (m.cmd == A_CLSE) {
                if (remote != 0) {
                    try { send(A_CLSE, id, remote, null); } catch (IOException e) { }
                }
                return sb.toString();
            }
        }
    }
}
