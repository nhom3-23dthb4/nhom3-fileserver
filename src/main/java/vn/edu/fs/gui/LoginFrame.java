package vn.edu.fs.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.server.TlsConfig;

/** Màn đăng nhập: host, cổng, ID, mật khẩu. Mạng chạy ở luồng nền (SwingWorker) nên cửa sổ không bị đơ. */
public class LoginFrame extends JFrame {

    private final JTextField host = new JTextField("localhost", 16);
    private final JTextField port = new JTextField("8443", 6);      // đổi cho đúng cổng server
    private final JTextField user = new JTextField(16);
    private final JPasswordField pass = new JPasswordField(16);
    private final JButton loginBtn = new JButton("Đăng nhập");
    private final JLabel status = new JLabel(" ");

    public LoginFrame() {
        super("File Server – Đăng nhập");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        addRow(form, c, 0, "Máy chủ", host);
        addRow(form, c, 1, "Cổng", port);
        addRow(form, c, 2, "ID", user);
        addRow(form, c, 3, "Mật khẩu", pass);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(loginBtn, BorderLayout.EAST);

        add(form, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(loginBtn);       // Enter = đăng nhập
        loginBtn.addActionListener(e -> doLogin());
        pack();
        setLocationRelativeTo(null);
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridy = row;
        c.gridx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(field, c);
    }

    private void doLogin() {
        final String h = host.getText().trim();
        final String u = user.getText().trim();
        final int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException ex) {
            status.setText("Cổng phải là số.");
            return;
        }
        final char[] pw = pass.getPassword();
        if (u.isEmpty() || pw.length == 0) {
            Arrays.fill(pw, '\0');
            status.setText("Nhập ID và mật khẩu.");
            return;
        }
        loginBtn.setEnabled(false);
        status.setText("Đang kết nối...");

        new SwingWorker<FileClient, Void>() {
            @Override protected FileClient doInBackground() throws Exception {
                // Truststore và mật khẩu lấy từ biến môi trường, KHÔNG ghi cứng trong code
                Path trust = Path.of(System.getenv().getOrDefault("FS_TRUSTSTORE", "certs/truststore.p12"));
                String trustPw = System.getenv("FS_TRUSTSTORE_PASSWORD");
                if (trustPw == null) {
                    throw new IllegalStateException("Thiếu biến môi trường FS_TRUSTSTORE_PASSWORD");
                }
                SSLContext ctx = TlsConfig.clientContext(trust, trustPw.toCharArray());
                FileClient client = new FileClient(h, p, ctx);
                try {
                    client.login(u, new String(pw));
                } catch (Exception ex) {
                    client.close();                      // đăng nhập thất bại thì đóng kết nối, không để rò
                    throw ex;
                }
                return client;
            }

            @Override protected void done() {
                Arrays.fill(pw, '\0');                   // xóa mật khẩu khỏi bộ nhớ
                pass.setText("");
                loginBtn.setEnabled(true);
                try {
                    FileClient client = get();
                    new MainFrame(client, u).setVisible(true);
                    LoginFrame.this.dispose();
                } catch (Exception ex) {
                    status.setText(Errors.message(ex));
                }
            }
        }.execute();
    }
}